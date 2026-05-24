#include "AirPodsConnector.hpp"

#include "Logger.hpp"

#include <windows.h>
#include <bluetoothapis.h>
#pragma comment(lib, "Bthprops.lib")

#include <winrt/Windows.Devices.Bluetooth.h>
#include <winrt/Windows.Devices.Bluetooth.Rfcomm.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.Networking.Sockets.h>
#include <winrt/Windows.Networking.h>

#include <mmreg.h>
#include <mmdeviceapi.h>
#include <audiopolicy.h>
#include <functiondiscoverykeys_devpkey.h>
#include <propvarutil.h>
#include <wrl/client.h>

#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>
#include <regex>
#include <string>
#include <thread>
#include <vector>

// Undocumented IPolicyConfigVista interface for setting the default audio endpoint.
// Used by AudioSwitcher, SoundSwitch, NirCmd, and many open-source tools.
namespace {

const CLSID CLSID_CPolicyConfigVistaClient = {
    0x294935CEu, 0xF637, 0x4E7C,
    { 0xA4, 0x1B, 0xAB, 0x25, 0x54, 0x60, 0xB8, 0x62 }
};

struct DECLSPEC_UUID("568b9108-44bf-40b4-9006-86afe5b5a620")
IPolicyConfigVista : public IUnknown {
    virtual HRESULT STDMETHODCALLTYPE GetMixFormat(PCWSTR, WAVEFORMATEX**) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetDeviceFormat(PCWSTR, INT, WAVEFORMATEX**) = 0;
    virtual HRESULT STDMETHODCALLTYPE SetDeviceFormat(PCWSTR, WAVEFORMATEX*, WAVEFORMATEX*) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetProcessingPeriod(PCWSTR, INT, PINT64, PINT64) = 0;
    virtual HRESULT STDMETHODCALLTYPE SetProcessingPeriod(PCWSTR, PINT64) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetShareMode(PCWSTR, void*) = 0;
    virtual HRESULT STDMETHODCALLTYPE SetShareMode(PCWSTR, void*) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetPropertyValue(PCWSTR, const PROPERTYKEY&, PROPVARIANT*) = 0;
    virtual HRESULT STDMETHODCALLTYPE SetPropertyValue(PCWSTR, const PROPERTYKEY&, PROPVARIANT*) = 0;
    virtual HRESULT STDMETHODCALLTYPE SetDefaultEndpoint(PCWSTR wszDeviceId, ERole eRole) = 0;
    virtual HRESULT STDMETHODCALLTYPE SetEndpointVisibility(PCWSTR, INT) = 0;
};

}

namespace librepods {

namespace {

// A2DP Sink profile: 0000110B-0000-1000-8000-00805F9B34FB
static const GUID kA2dpSink = {
    0x0000110B, 0x0000, 0x1000,
    { 0x80, 0x00, 0x00, 0x80, 0x5F, 0x9B, 0x34, 0xFB }
};

HANDLE openFirstRadio() {
    BLUETOOTH_FIND_RADIO_PARAMS p = { sizeof(p) };
    HANDLE hRadio = nullptr;
    HBLUETOOTH_RADIO_FIND hFind = BluetoothFindFirstRadio(&p, &hRadio);
    if (hFind) BluetoothFindRadioClose(hFind);
    return hRadio;
}

}

AirPodsConnector::AirPodsConnector(std::uint64_t address) : m_address(address) {}

namespace {

// HandsFree profile: 0000111E-0000-1000-8000-00805F9B34FB
static const GUID kHandsFree = {
    0x0000111E, 0x0000, 0x1000,
    { 0x80, 0x00, 0x00, 0x80, 0x5F, 0x9B, 0x34, 0xFB }
};

// Dump everything Windows knows about the BT device + the GUID list of services
// it thinks the device advertises. Logged before/after each BluetoothSetServiceState
// call so that when it returns 87 (ERROR_INVALID_PARAMETER) we can tell at a glance
// whether the issue is pairing state, driver, or the kA2dpSink GUID simply not being
// in the installed-services list (which would explain error 87 — Windows believes
// the device doesn't expose A2DP).
void logDeviceInfo(std::uint64_t address, const char* tag) {
    HANDLE hRadio = openFirstRadio();
    if (!hRadio) {
        log::warn("logDeviceInfo[{}]: no Bluetooth radio", tag);
        return;
    }

    BLUETOOTH_DEVICE_INFO info = {};
    info.dwSize = sizeof(info);
    info.Address.ullLong = address;

    DWORD getErr = BluetoothGetDeviceInfo(hRadio, &info);
    if (getErr != ERROR_SUCCESS) {
        log::warn("logDeviceInfo[{}]: BluetoothGetDeviceInfo({:012X}) failed: {} (0x{:08X})",
            tag, address, getErr, getErr);
        CloseHandle(hRadio);
        return;
    }
    log::info("  [BT-diag {}] device {:012X}: connected={} authenticated={} remembered={} class=0x{:08X}",
        tag,
        address,
        info.fConnected ? "yes" : "no",
        info.fAuthenticated ? "yes" : "no",
        info.fRemembered ? "yes" : "no",
        info.ulClassofDevice);

    // Probe for service count, then fetch the list.
    DWORD numServices = 0;
    DWORD enumErr = BluetoothEnumerateInstalledServices(hRadio, &info, &numServices, nullptr);
    if (enumErr != ERROR_SUCCESS && enumErr != ERROR_MORE_DATA) {
        log::warn("  [BT-diag {}] BluetoothEnumerateInstalledServices(probe) failed: {} (0x{:08X})",
            tag, enumErr, enumErr);
        CloseHandle(hRadio);
        return;
    }

    bool a2dpAdvertised = false;
    bool hfpAdvertised = false;
    if (numServices > 0) {
        std::vector<GUID> services(numServices);
        enumErr = BluetoothEnumerateInstalledServices(hRadio, &info, &numServices, services.data());
        if (enumErr == ERROR_SUCCESS) {
            log::info("  [BT-diag {}] installed services on {:012X}: {} entries",
                tag, address, numServices);
            for (DWORD i = 0; i < numServices; ++i) {
                const GUID& g = services[i];
                wchar_t guidStr[64] = {};
                StringFromGUID2(g, guidStr, 64);
                bool isA2dp = IsEqualGUID(g, kA2dpSink) != FALSE;
                bool isHfp  = IsEqualGUID(g, kHandsFree) != FALSE;
                a2dpAdvertised = a2dpAdvertised || isA2dp;
                hfpAdvertised  = hfpAdvertised  || isHfp;
                log::debug("    service[{}] = {} {}",
                    i,
                    winrt::to_string(guidStr),
                    isA2dp ? "[A2DP_SINK]" : isHfp ? "[HandsFree]" : "");
            }
        } else {
            log::warn("  [BT-diag {}] BluetoothEnumerateInstalledServices(payload) failed: {} (0x{:08X})",
                tag, enumErr, enumErr);
        }
    }
    log::info("  [BT-diag {}] A2DP advertised: {} | HFP advertised: {}",
        tag,
        a2dpAdvertised ? "yes" : "NO",
        hfpAdvertised  ? "yes" : "NO");

    CloseHandle(hRadio);

    // Cross-reference with the WinRT view.
    try {
        using namespace winrt::Windows::Devices::Bluetooth;
        auto device = BluetoothDevice::FromBluetoothAddressAsync(address).get();
        if (device) {
            log::info("  [BT-diag {}] WinRT BluetoothDevice.ConnectionStatus = {}",
                tag, (int)device.ConnectionStatus());
        } else {
            log::info("  [BT-diag {}] WinRT BluetoothDevice = null", tag);
        }
    } catch (const winrt::hresult_error& e) {
        log::debug("  [BT-diag {}] WinRT lookup failed: 0x{:08X}",
            tag, (std::uint32_t)e.code().value);
    }
}

// `quiet=true` suppresses the success log line and the [BT-diag] dump on failure —
// used by the periodic A2DP-nudge thread that runs after connect() to repeatedly
// poke the BT stack until the audio endpoint reaches ACTIVE. Without quiet=true,
// the log floods with diagnostics every 3 seconds during the negotiation wait.
bool toggleProfile(std::uint64_t address, const GUID& profile, DWORD state,
                   const char* label, bool quiet = false) {
    HANDLE hRadio = openFirstRadio();
    if (!hRadio) {
        if (!quiet) log::error("No Bluetooth radio found");
        return false;
    }

    BLUETOOTH_DEVICE_INFO info = {};
    info.dwSize = sizeof(info);
    info.Address.ullLong = address;

    // Populate class-of-device and other fields from the system's paired-device record.
    DWORD getErr = BluetoothGetDeviceInfo(hRadio, &info);
    if (getErr != ERROR_SUCCESS) {
        if (!quiet) {
            log::warn("BluetoothGetDeviceInfo({:012X}) returned {} (0x{:08X}) — device may not be paired",
                address, getErr, getErr);
        }
        CloseHandle(hRadio);
        return false;
    }
    if (!quiet) {
        log::debug("  device: connected={} class=0x{:08X}",
            info.fConnected ? "yes" : "no",
            info.ulClassofDevice);
    }

    DWORD setErr = BluetoothSetServiceState(hRadio, &info, &profile, state);
    CloseHandle(hRadio);

    if (setErr != ERROR_SUCCESS) {
        if (!quiet) {
            log::warn("BluetoothSetServiceState({}, {}) returned {} (0x{:08X})",
                label, state == BLUETOOTH_SERVICE_ENABLE ? "ENABLE" : "DISABLE",
                setErr, setErr);
            // The call just failed — dump everything Windows knows about the device.
            // If the kA2dpSink GUID is not in the installed-services list below, then
            // error 87 = ERROR_INVALID_PARAMETER is "explained" (Windows believes the
            // device doesn't advertise this service) and the next step is to inspect
            // the SDP records / re-pair the device.
            std::string diagTag = std::string(label) + " post-fail";
            logDeviceInfo(address, diagTag.c_str());
        }
        return false;
    }
    return true;
}

}

bool AirPodsConnector::connect() {
    if (m_address == 0) {
        log::warn("AirPods address not configured; use tray menu \"Select AirPods...\"");
        return false;
    }
    std::lock_guard<std::mutex> lk(m_mutex);
    log::debug("AirPodsConnector::connect() address={:012X}", m_address);

    using namespace winrt::Windows::Devices::Bluetooth;
    using namespace winrt::Windows::Devices::Bluetooth::Rfcomm;
    using namespace winrt::Windows::Networking::Sockets;

    BluetoothDevice device = nullptr;
    try {
        device = BluetoothDevice::FromBluetoothAddressAsync(m_address).get();
        if (!device) {
            log::warn("AirPods FromBluetoothAddressAsync returned null");
            return false;
        }
        log::debug("  status before: connection={}", (int)device.ConnectionStatus());
    } catch (const winrt::hresult_error& e) {
        log::warn("FromBluetoothAddressAsync failed: 0x{:08X} {}",
            (std::uint32_t)e.code().value, winrt::to_string(e.message()));
        return false;
    }

    // Order matters here. AirPods are typically paired but disconnected at the
    // ACL layer when the user puts them back in their ears. BluetoothSetService-
    // State CANNOT bring up the classical-BT link; it only toggles profile state
    // on an already-connected device. So:
    //
    //   1. FIRST: uncached RFCOMM enumeration. This forces a fresh L2CAP+SDP
    //      exchange (10-15s) which side-effect-establishes the classical-BT link.
    //      Opening a socket on a returned service is best-effort: some drivers
    //      need the socket to fully wake up; others activate on SDP alone.
    //
    //   2. THEN: BluetoothSetServiceState(A2DP/HFP, ENABLE) — the documented
    //      profile-activation path. Now that the link is up (per step 1), this
    //      call has a real chance of succeeding rather than returning error 87
    //      (ERROR_INVALID_PARAMETER, which in practice means "device not
    //      currently connected at the BT layer"). Logs full BT diagnostics if
    //      it still fails — see logDeviceInfo in toggleProfile.
    //
    // After this returns, the IMMNotificationClient is responsible for finishing
    // the routing when (if) the audio endpoint transitions to DEVICE_STATE_ACTIVE.
    // This function no longer waits for that — it just kicks the OS.

    // Step 1: bring up the BT classical link via uncached SDP / RFCOMM.
    bool socketOpened = false;
    try {
        auto services = device.GetRfcommServicesAsync(BluetoothCacheMode::Uncached).get();
        log::info("RFCOMM services discovered (uncached): {}", services.Services().Size());
        for (auto&& svc : services.Services()) {
            try {
                log::debug("  opening RFCOMM service '{}'...",
                    winrt::to_string(svc.ConnectionServiceName()));
                StreamSocket sock;
                sock.ConnectAsync(svc.ConnectionHostName(), svc.ConnectionServiceName()).get();
                socketOpened = true;
                log::info("RFCOMM socket opened — BT classical link should now be up");
                sock.Close();
                break;
            } catch (const winrt::hresult_error& e) {
                log::debug("    open failed: 0x{:08X} {}",
                    (std::uint32_t)e.code().value, winrt::to_string(e.message()));
            }
        }
        if (!socketOpened && services.Services().Size() == 0) {
            log::debug("No RFCOMM services advertised right now — A2DP profile activation may still bring up the link");
        }
    } catch (const winrt::hresult_error& e) {
        log::debug("RFCOMM enumeration failed (non-fatal): 0x{:08X} {}",
            (std::uint32_t)e.code().value, winrt::to_string(e.message()));
    }

    // Log the link state between steps to confirm step 1 actually brought it up.
    try {
        log::debug("  status mid-connect: connection={}", (int)device.ConnectionStatus());
    } catch (...) {}

    // Step 2: activate A2DP/HFP profiles on the (now hopefully connected) device.
    bool a2dpKicked = toggleProfile(m_address, kA2dpSink,  BLUETOOTH_SERVICE_ENABLE, "A2DP");
    bool hfpKicked  = toggleProfile(m_address, kHandsFree, BLUETOOTH_SERVICE_ENABLE, "HFP");
    if (!a2dpKicked && !hfpKicked) {
        log::warn("Both BluetoothSetServiceState calls failed after RFCOMM probe — "
                  "see [BT-diag] lines above; if connected=no, the link never came up");
    }

    try {
        log::debug("  status after: connection={}", (int)device.ConnectionStatus());
    } catch (...) {}

    const bool kicked = a2dpKicked || hfpKicked || socketOpened;

    // Profile nudge: even after a successful RFCOMM/SDP trigger, Windows can take
    // 20-40 seconds to finally promote the audio endpoint to DEVICE_STATE_ACTIVE
    // (the BT classical link is up, but A2DP negotiation is still bouncing between
    // Pixel and Windows). Retrying BluetoothSetServiceState every few seconds
    // sometimes nudges the stack to negotiate sooner. Failures (err 87 "already
    // enabled" etc.) are silenced so the log isn't flooded; the actual routing
    // application happens via the IMMNotificationClient when the endpoint hits
    // ACTIVE, independently of these nudges.
    //
    // Guard against multiple concurrent nudgers: HandoverController calls connect()
    // up to 3 times across the retry backoffs. We only want one nudger running at a
    // time per process.
    static std::atomic<bool> s_nudgerRunning{false};
    if (kicked && !s_nudgerRunning.exchange(true)) {
        std::thread([addr = m_address]() {
            for (int i = 0; i < 6; ++i) {
                std::this_thread::sleep_for(std::chrono::seconds(3));
                log::debug("Profile nudge attempt {}/6 (quiet)", i + 1);
                toggleProfile(addr, kA2dpSink, BLUETOOTH_SERVICE_ENABLE,
                              "A2DP-nudge", /*quiet=*/true);
            }
            s_nudgerRunning = false;
        }).detach();
    }

    // The connect attempt succeeded if *any* trigger fired. The audio endpoint
    // transition (and the routing) is now the IMMNotificationClient's job.
    return kicked;
}

namespace {

using Microsoft::WRL::ComPtr;

// Lowercase ASCII fold for case-insensitive substring search.
std::wstring foldLower(std::wstring_view s) {
    std::wstring out;
    out.reserve(s.size());
    for (wchar_t c : s) {
        if (c >= L'A' && c <= L'Z') c = (wchar_t)(c + (L'a' - L'A'));
        out.push_back(c);
    }
    return out;
}

const char* stateName(DWORD s) {
    switch (s) {
        case DEVICE_STATE_ACTIVE:     return "ACTIVE";
        case DEVICE_STATE_DISABLED:   return "DISABLED";
        case DEVICE_STATE_NOTPRESENT: return "NOTPRESENT";
        case DEVICE_STATE_UNPLUGGED:  return "UNPLUGGED";
        default:                      return "??";
    }
}

struct Endpoint {
    std::wstring id;
    DWORD state;
};

// Enumerate endpoints in ALL states (active+disabled+notpresent+unplugged) and
// return ones whose friendly name (case-insensitive) contains "airpods".
std::vector<Endpoint> findAirPodsEndpoints(IMMDeviceEnumerator* enumerator, EDataFlow flow) {
    std::vector<Endpoint> hits;
    ComPtr<IMMDeviceCollection> col;
    if (FAILED(enumerator->EnumAudioEndpoints(flow, DEVICE_STATEMASK_ALL, &col))) return hits;

    UINT count = 0;
    col->GetCount(&count);
    for (UINT i = 0; i < count; ++i) {
        ComPtr<IMMDevice> dev;
        if (FAILED(col->Item(i, &dev))) continue;

        LPWSTR rawId = nullptr;
        if (FAILED(dev->GetId(&rawId)) || !rawId) continue;
        std::wstring idStr = rawId;
        CoTaskMemFree(rawId);

        DWORD state = 0;
        dev->GetState(&state);

        ComPtr<IPropertyStore> props;
        if (FAILED(dev->OpenPropertyStore(STGM_READ, &props))) continue;

        PROPVARIANT name;
        PropVariantInit(&name);
        std::wstring friendly;
        if (SUCCEEDED(props->GetValue(PKEY_Device_FriendlyName, &name)) && name.vt == VT_LPWSTR && name.pwszVal) {
            friendly = name.pwszVal;
        }
        PropVariantClear(&name);

        if (friendly.empty()) continue;

        const bool isAirPods = foldLower(friendly).find(L"airpods") != std::wstring::npos;
        if (isAirPods) {
            log::info("    [match] endpoint flow={} state={} name='{}'",
                flow == eRender ? "render" : "capture",
                stateName(state),
                winrt::to_string(friendly));
            hits.push_back({idStr, state});
        } else {
            log::debug("    endpoint flow={} state={} name='{}'",
                flow == eRender ? "render" : "capture",
                stateName(state),
                winrt::to_string(friendly));
        }
    }
    return hits;
}

// Update Teams' cmd_settings.json so it uses the specified audio endpoints.
// New Teams (Store edition) stores fixed device IDs here and does not expose a
// "system default" option in its UI. Writing these IDs before the user answers
// the incoming call routes Teams audio to AirPods for that session.
void updateTeamsDeviceConfig(const std::wstring& renderEpId, const std::wstring& captureEpId) {
    wchar_t localAppData[MAX_PATH] = {};
    if (!GetEnvironmentVariableW(L"LOCALAPPDATA", localAppData, MAX_PATH)) return;

    const std::filesystem::path cfgPath =
        std::filesystem::path{localAppData}
        / L"Packages" / L"MSTeams_8wekyb3d8bbwe"
        / L"LocalCache" / L"Microsoft" / L"MSTeams" / L"cmd_settings.json";

    if (!std::filesystem::exists(cfgPath)) {
        log::debug("Teams cmd_settings.json not found — skipping Teams device update");
        return;
    }

    std::string content;
    {
        std::ifstream in(cfgPath);
        if (!in.is_open()) {
            log::warn("Cannot open Teams cmd_settings.json for reading");
            return;
        }
        content.assign(std::istreambuf_iterator<char>(in), {});
    }

    // Device IDs consist of ASCII hex digits, braces, dots, and dashes — safe to narrow.
    const std::string render  = winrt::to_string(renderEpId);
    const std::string capture = winrt::to_string(captureEpId);

    try {
        // Replace calling_selected_speaker_device value
        content = std::regex_replace(content,
            std::regex(R"("calling_selected_speaker_device":\{"Speaker":"[^"]*"\})"),
            "\"calling_selected_speaker_device\":{\"Speaker\":\"" + render + "\"}");

        // Replace calling_selected_microphone_device value
        content = std::regex_replace(content,
            std::regex(R"("calling_selected_microphone_device":\{"Microphone":"[^"]*"\})"),
            "\"calling_selected_microphone_device\":{\"Microphone\":\"" + capture + "\"}");

        // Replace Microphone+Speaker within calling_user_selected_devices, preserving Camera etc.
        content = std::regex_replace(content,
            std::regex(R"("calling_user_selected_devices":\{"Microphone":"[^"]*","Speaker":"[^"]*")"),
            "\"calling_user_selected_devices\":{\"Microphone\":\"" + capture
            + "\",\"Speaker\":\"" + render + "\"");
    } catch (const std::regex_error& e) {
        log::warn("Teams config regex error: {}", e.what());
        return;
    }

    {
        std::ofstream out(cfgPath, std::ios::trunc);
        if (!out.is_open()) {
            log::warn("Cannot write Teams cmd_settings.json");
            return;
        }
        out << content;
    }
    log::info("Teams device config updated — speaker={} mic={}", render, capture);
}

// Apply a single endpoint as the default for all three audio roles
// (Console, Multimedia, Communications). Logs per-role result. If the endpoint
// is not in DEVICE_STATE_ACTIVE, first calls SetEndpointVisibility to try to
// promote it. Returns true if at least one SetDefaultEndpoint succeeded.
bool applyEndpointAsDefault(IPolicyConfigVista* policy,
                            const Endpoint& ep,
                            const char* label) {
    if (ep.state != DEVICE_STATE_ACTIVE) {
        log::info("  {} endpoint is {} — making visible via SetEndpointVisibility",
            label, stateName(ep.state));
        HRESULT vr = policy->SetEndpointVisibility(ep.id.c_str(), 1);
        if (FAILED(vr)) {
            log::warn("  SetEndpointVisibility({}) failed: 0x{:08X}",
                label, (std::uint32_t)vr);
        }
    }
    bool anyOk = false;
    for (ERole role : { eConsole, eMultimedia, eCommunications }) {
        HRESULT r = policy->SetDefaultEndpoint(ep.id.c_str(), role);
        const char* roleName = role == eConsole ? "Console" :
                               role == eMultimedia ? "Multimedia" : "Communications";
        if (SUCCEEDED(r)) {
            log::info("Set AirPods as default {} ({} role)", label, roleName);
            anyOk = true;
        } else {
            log::warn("SetDefaultEndpoint({}, {}) failed: 0x{:08X}",
                label, roleName, (std::uint32_t)r);
        }
    }
    return anyOk;
}

// Broadcast WM_WININICHANGE("Mmsys.cpl") — wakes apps registered for
// default-device-change notifications (Teams, Chrome, etc).
void broadcastDefaultDeviceChange() {
    DWORD recipients = BSM_APPLICATIONS;
    int sent = BroadcastSystemMessageW(
        BSF_POSTMESSAGE | BSF_IGNORECURRENTTASK,
        &recipients,
        WM_WININICHANGE,
        0,
        reinterpret_cast<LPARAM>(L"Mmsys.cpl"));
    log::debug("BroadcastSystemMessage(Mmsys.cpl) sent={}", sent);
}

// Single-pass routing: enumerate AirPods endpoints, apply any that are ACTIVE,
// broadcast the change, and patch Teams config. Returns true if any side was
// applied. Safe to call from any thread that has CoInitialize'd.
bool tryApplyAirPodsRouting(IMMDeviceEnumerator* enumerator,
                            IPolicyConfigVista* policy) {
    auto renderHits  = findAirPodsEndpoints(enumerator, eRender);
    auto captureHits = findAirPodsEndpoints(enumerator, eCapture);

    bool anyApplied = false;
    std::wstring activeRenderId, activeCaptureId;

    for (auto& ep : renderHits) {
        if (ep.state != DEVICE_STATE_ACTIVE) continue;
        if (applyEndpointAsDefault(policy, ep, "render (output)")) {
            anyApplied = true;
            if (activeRenderId.empty()) activeRenderId = ep.id;
        }
    }
    for (auto& ep : captureHits) {
        if (ep.state != DEVICE_STATE_ACTIVE) continue;
        if (applyEndpointAsDefault(policy, ep, "capture (input)")) {
            anyApplied = true;
            if (activeCaptureId.empty()) activeCaptureId = ep.id;
        }
    }

    if (anyApplied) {
        broadcastDefaultDeviceChange();
        if (!activeRenderId.empty() || !activeCaptureId.empty()) {
            updateTeamsDeviceConfig(activeRenderId, activeCaptureId);
        }
    }
    return anyApplied;
}

// IMMNotificationClient that listens for endpoint state changes and applies
// AirPods routing the moment the OS promotes an AirPods endpoint to ACTIVE.
// This is the Windows analogue of Android's AudioDeviceCallback.
//
// Two modes:
//
//   TRANSIENT (default): arm() opens a 120-second window. While armed, any
//   AirPods endpoint transition to ACTIVE triggers routing; once routing
//   applies, the notifier disarms itself. If the window expires before any
//   ACTIVE transition, a diagnostic warning fires.
//
//   PERSISTENT (set via setPersistent(true)): the arm never expires, and
//   re-arms automatically after each successful routing. Used while we hold
//   OwnershipState::LocalPc — so when AirPods bounce off Windows mid-call
//   (Apple auto-switch to the phone, brief BT blips) and then come back, the
//   routing reapplies without needing another setAsDefaultAudioDevice() call.
//   HandoverController toggles this with the LocalPc/RemoteAndroid state.
//
// 120s transient window is sized for the worst-case observed reconnect race:
// after we call connect(), AirPods can bounce to the phone for ~30s before
// settling on Windows. A shorter window (the old 30s) frequently expired
// milliseconds before the ACTIVE transition arrived.
//
// Lifetime: singleton, registered once and leaked at process exit
// (the WASAPI samples and most production code do the same).
class EndpointNotifier : public IMMNotificationClient {
public:
    static constexpr std::chrono::seconds kArmWindow{120};

    void arm() {
        std::lock_guard<std::mutex> lk(m_mtx);
        m_armed = true;
        m_armedAt = std::chrono::steady_clock::now();
        log::debug("EndpointNotifier: armed ({}s window, persistent={})",
            kArmWindow.count(), m_persistent ? "yes" : "no");
    }

    // Persistent mode: armed indefinitely until cleared. After a successful
    // routing application, the notifier re-arms (instead of disarming) so
    // further UNPLUGGED→ACTIVE cycles also route. Called by HandoverController
    // when transitioning to/from OwnershipState::LocalPc.
    void setPersistent(bool persistent) {
        std::lock_guard<std::mutex> lk(m_mtx);
        if (m_persistent == persistent) return;
        m_persistent = persistent;
        log::info("EndpointNotifier: persistent mode {}", persistent ? "ENABLED" : "DISABLED");
        if (persistent) {
            // Auto-arm so the caller doesn't have to also call arm().
            m_armed = true;
            m_armedAt = std::chrono::steady_clock::now();
        }
    }

    bool isArmedAndFresh() {
        std::lock_guard<std::mutex> lk(m_mtx);
        if (!m_armed) return false;
        if (m_persistent) return true;  // never expires in persistent mode
        auto age = std::chrono::steady_clock::now() - m_armedAt;
        if (age >= kArmWindow) {
            m_armed = false;
            log::warn("EndpointNotifier: arm window expired ({}s) — AirPods endpoint never reached ACTIVE. "
                      "A2DP profile activation has failed at the OS level, or the OS reconnect race "
                      "took longer than the window. Check the [BT-diag] log lines above.",
                      kArmWindow.count());
            return false;
        }
        return true;
    }

    void setEnumerator(ComPtr<IMMDeviceEnumerator> e) {
        std::lock_guard<std::mutex> lk(m_mtx);
        m_enumerator = std::move(e);
    }

    // IUnknown — singleton; ref-counting is a no-op for lifetime purposes.
    ULONG STDMETHODCALLTYPE AddRef() override {
        return InterlockedIncrement(&m_ref);
    }
    ULONG STDMETHODCALLTYPE Release() override {
        return InterlockedDecrement(&m_ref);
    }
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** ppv) override {
        if (!ppv) return E_POINTER;
        if (riid == __uuidof(IUnknown) || riid == __uuidof(IMMNotificationClient)) {
            *ppv = static_cast<IMMNotificationClient*>(this);
            AddRef();
            return S_OK;
        }
        *ppv = nullptr;
        return E_NOINTERFACE;
    }

    // IMMNotificationClient
    HRESULT STDMETHODCALLTYPE OnDeviceStateChanged(LPCWSTR pwstrDeviceId, DWORD dwNewState) override {
        if (!pwstrDeviceId) return S_OK;
        log::debug("IMMNotificationClient::OnDeviceStateChanged: id='{}' -> {}",
            winrt::to_string(pwstrDeviceId), stateName(dwNewState));
        if (dwNewState == DEVICE_STATE_ACTIVE) {
            maybeApplyForDevice(pwstrDeviceId);
        }
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE OnDeviceAdded(LPCWSTR pwstrDeviceId) override {
        if (!pwstrDeviceId) return S_OK;
        log::debug("IMMNotificationClient::OnDeviceAdded: id='{}'",
            winrt::to_string(pwstrDeviceId));
        maybeApplyForDevice(pwstrDeviceId);
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE OnDeviceRemoved(LPCWSTR) override { return S_OK; }
    HRESULT STDMETHODCALLTYPE OnDefaultDeviceChanged(EDataFlow, ERole, LPCWSTR) override { return S_OK; }
    HRESULT STDMETHODCALLTYPE OnPropertyValueChanged(LPCWSTR, const PROPERTYKEY) override { return S_OK; }

private:
    LONG m_ref = 1;
    std::mutex m_mtx;
    bool m_armed = false;
    bool m_persistent = false;
    std::chrono::steady_clock::time_point m_armedAt;
    ComPtr<IMMDeviceEnumerator> m_enumerator;

    // If the device named in the callback is an AirPods endpoint and we are
    // armed, run the full routing pass. Held lock released before heavy work
    // (CoCreateInstance, SetDefaultEndpoint) so we don't serialize callbacks.
    void maybeApplyForDevice(LPCWSTR id) {
        if (!isArmedAndFresh()) return;

        ComPtr<IMMDeviceEnumerator> enumerator;
        {
            std::lock_guard<std::mutex> lk(m_mtx);
            enumerator = m_enumerator;
        }
        if (!enumerator) return;

        // Quick filter: only proceed if this id is actually an AirPods endpoint.
        // The callback fires for every audio device state change on the system
        // (USB mics, HDMI displays, etc.), so we don't want to do COM heavy
        // lifting unless this is one of ours.
        ComPtr<IMMDevice> dev;
        if (FAILED(enumerator->GetDevice(id, &dev))) return;
        ComPtr<IPropertyStore> props;
        if (FAILED(dev->OpenPropertyStore(STGM_READ, &props))) return;
        PROPVARIANT name;
        PropVariantInit(&name);
        std::wstring friendly;
        if (SUCCEEDED(props->GetValue(PKEY_Device_FriendlyName, &name)) &&
            name.vt == VT_LPWSTR && name.pwszVal) {
            friendly = name.pwszVal;
        }
        PropVariantClear(&name);
        if (foldLower(friendly).find(L"airpods") == std::wstring::npos) return;

        log::info("Endpoint notifier: AirPods endpoint '{}' just hit ACTIVE — routing now",
            winrt::to_string(friendly));

        ComPtr<IPolicyConfigVista> policy;
        HRESULT hr = CoCreateInstance(CLSID_CPolicyConfigVistaClient, nullptr, CLSCTX_ALL,
            __uuidof(IPolicyConfigVista), (void**)policy.GetAddressOf());
        if (FAILED(hr)) {
            log::warn("Notifier: CoCreateInstance(IPolicyConfigVista) failed: 0x{:08X}",
                (std::uint32_t)hr);
            return;
        }

        bool applied = tryApplyAirPodsRouting(enumerator.Get(), policy.Get());
        if (applied) {
            std::lock_guard<std::mutex> lk(m_mtx);
            if (m_persistent) {
                // Stay armed so subsequent UNPLUGGED→ACTIVE cycles also route
                // (e.g. AirPods bounce to phone briefly mid-call and come back).
                m_armedAt = std::chrono::steady_clock::now();
                log::info("Endpoint notifier: routing applied — staying armed (persistent mode)");
            } else {
                m_armed = false;
                log::info("Endpoint notifier: routing applied — disarmed");
            }
        }
    }
};

// Singleton notifier. Created on first use, leaked at process exit.
EndpointNotifier* g_notifier = nullptr;
std::once_flag g_notifierInit;

// Register the singleton notifier with the audio system the first time we're
// called; refresh its enumerator pointer every call so subsequent runs use
// the latest CoCreateInstance result.
EndpointNotifier* ensureNotifier(IMMDeviceEnumerator* enumerator) {
    std::call_once(g_notifierInit, [enumerator]() {
        g_notifier = new EndpointNotifier();
        HRESULT hr = enumerator->RegisterEndpointNotificationCallback(g_notifier);
        if (FAILED(hr)) {
            log::error("RegisterEndpointNotificationCallback failed: 0x{:08X}",
                (std::uint32_t)hr);
            delete g_notifier;
            g_notifier = nullptr;
            return;
        }
        log::info("Endpoint notification callback registered — event-driven AirPods routing active");
    });
    if (g_notifier) {
        ComPtr<IMMDeviceEnumerator> copy = enumerator;
        g_notifier->setEnumerator(std::move(copy));
    }
    return g_notifier;
}

}

// Event-driven: arms the IMMNotificationClient (registered lazily on first
// call) so that the moment any AirPods endpoint transitions to ACTIVE, the
// full routing pass fires. Also does a synchronous pass for the case where
// the endpoint is already ACTIVE by the time we get here (e.g. retry after
// a 3s delay in HandoverController). Returns immediately — no blocking poll.
//
// This is the Windows equivalent of Android's AudioDeviceCallback:
// MediaController.kt registers `audioManager.registerAudioDeviceCallback(...)`
// and waits for `onAudioDevicesAdded(TYPE_BLUETOOTH_A2DP)` to fire. Here we
// register an IMMNotificationClient and wait for OnDeviceStateChanged on an
// AirPods endpoint to fire with DEVICE_STATE_ACTIVE.
bool AirPodsConnector::setAsDefaultAudioDevice() {
    HRESULT coInitHr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    // S_FALSE means already initialized on this thread — that's fine.
    const bool weInited = (coInitHr == S_OK);

    ComPtr<IMMDeviceEnumerator> enumerator;
    HRESULT hr = CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL,
                                  __uuidof(IMMDeviceEnumerator), (void**)enumerator.GetAddressOf());
    if (FAILED(hr)) {
        log::warn("CoCreateInstance(MMDeviceEnumerator) failed: 0x{:08X}", (std::uint32_t)hr);
        if (weInited) CoUninitialize();
        return false;
    }

    ComPtr<IPolicyConfigVista> policy;
    hr = CoCreateInstance(CLSID_CPolicyConfigVistaClient, nullptr, CLSCTX_ALL,
                          __uuidof(IPolicyConfigVista), (void**)policy.GetAddressOf());
    if (FAILED(hr)) {
        log::warn("CoCreateInstance(IPolicyConfigVista) failed: 0x{:08X}", (std::uint32_t)hr);
        if (weInited) CoUninitialize();
        return false;
    }

    // Register the IMMNotificationClient on first call. Arm it so a future
    // OnDeviceStateChanged(ACTIVE) on any AirPods endpoint will trigger
    // tryApplyAirPodsRouting automatically.
    EndpointNotifier* notifier = ensureNotifier(enumerator.Get());
    if (notifier) notifier->arm();

    // Synchronous fast path: if any AirPods endpoint is *already* in ACTIVE
    // state right now, route immediately. This handles the retry case (caller
    // called us 3s after connect, by which point the endpoint may already be up)
    // and also the case where we just missed a state-change callback firing on
    // another thread.
    bool appliedNow = tryApplyAirPodsRouting(enumerator.Get(), policy.Get());

    if (appliedNow) {
        log::info("setAsDefaultAudioDevice: AirPods endpoint was already ACTIVE — routed synchronously");
    } else if (notifier) {
        log::info("setAsDefaultAudioDevice: no AirPods endpoint ACTIVE yet — "
                  "waiting for OnDeviceStateChanged callback ({}s window)",
                  EndpointNotifier::kArmWindow.count());
    } else {
        // Notifier registration failed (very rare). Fall back to a best-effort
        // synchronous scan — but do NOT apply UNPLUGGED endpoints. Better to
        // do nothing than to set a dead device as default (which causes apps
        // to mute audio rather than fall back to speakers).
        log::warn("setAsDefaultAudioDevice: notifier unavailable — audio may not route until "
                  "the user manually switches output device");
    }

    if (weInited) CoUninitialize();
    // Return true if we routed or if the notifier is now armed for a future
    // routing. The caller (HandoverController) doesn't distinguish — either way
    // the system is making best effort to route.
    return appliedNow || (notifier != nullptr);
}

void AirPodsConnector::setPersistentArm(bool persistent) {
    // We need an enumerator to register the notifier (lazy init in ensureNotifier).
    // If the notifier is already registered, ensureNotifier will just refresh the
    // enumerator pointer. If it isn't, this is the first-ever call — make sure we
    // can register it now so persistent mode takes effect immediately.
    HRESULT coInitHr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    const bool weInited = (coInitHr == S_OK);

    ComPtr<IMMDeviceEnumerator> enumerator;
    HRESULT hr = CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL,
                                  __uuidof(IMMDeviceEnumerator), (void**)enumerator.GetAddressOf());
    if (SUCCEEDED(hr)) {
        if (EndpointNotifier* notifier = ensureNotifier(enumerator.Get())) {
            notifier->setPersistent(persistent);
        } else {
            log::warn("setPersistentArm: notifier registration failed; persistent={} ignored",
                persistent);
        }
    } else {
        log::warn("setPersistentArm: CoCreateInstance(MMDeviceEnumerator) failed: 0x{:08X}",
            (std::uint32_t)hr);
    }

    if (weInited) CoUninitialize();
}

bool AirPodsConnector::hasActiveAudioSessions() {
    HRESULT coInitHr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    const bool weInited = (coInitHr == S_OK);

    bool active = false;

    ComPtr<IMMDeviceEnumerator> enumerator;
    HRESULT hr = CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL,
                                  __uuidof(IMMDeviceEnumerator), (void**)enumerator.GetAddressOf());
    if (SUCCEEDED(hr)) {
        // Check the render (output) endpoint — calls and meetings produce output audio.
        for (auto& ep : findAirPodsEndpoints(enumerator.Get(), eRender)) {
            if (ep.state != DEVICE_STATE_ACTIVE) continue;
            ComPtr<IMMDevice> dev;
            if (FAILED(enumerator->GetDevice(ep.id.c_str(), &dev))) continue;

            ComPtr<IAudioSessionManager2> mgr;
            if (FAILED(dev->Activate(__uuidof(IAudioSessionManager2), CLSCTX_ALL,
                                     nullptr, (void**)mgr.GetAddressOf()))) continue;

            ComPtr<IAudioSessionEnumerator> sessions;
            if (FAILED(mgr->GetSessionEnumerator(&sessions))) continue;

            int count = 0;
            sessions->GetCount(&count);
            for (int i = 0; i < count && !active; ++i) {
                ComPtr<IAudioSessionControl> ctrl;
                if (FAILED(sessions->GetSession(i, &ctrl))) continue;
                AudioSessionState state{};
                if (SUCCEEDED(ctrl->GetState(&state)) && state == AudioSessionStateActive) {
                    active = true;
                    log::debug("hasActiveAudioSessions: found active session on AirPods render endpoint");
                }
            }
            if (active) break;
        }
    }

    if (weInited) CoUninitialize();
    return active;
}

bool AirPodsConnector::isClassicallyConnected() {
    if (m_address == 0) return false;
    HANDLE hRadio = openFirstRadio();
    if (!hRadio) return false;
    BLUETOOTH_DEVICE_INFO info = {};
    info.dwSize = sizeof(info);
    info.Address.ullLong = m_address;
    DWORD err = BluetoothGetDeviceInfo(hRadio, &info);
    CloseHandle(hRadio);
    return err == ERROR_SUCCESS && info.fConnected;
}

bool AirPodsConnector::disconnect() {
    if (m_address == 0) return false;
    std::lock_guard<std::mutex> lk(m_mutex);
    log::debug("AirPodsConnector::disconnect() address={:012X}", m_address);

    bool a2dpOk = toggleProfile(m_address, kA2dpSink,  BLUETOOTH_SERVICE_DISABLE, "A2DP");
    bool hfpOk  = toggleProfile(m_address, kHandsFree, BLUETOOTH_SERVICE_DISABLE, "HFP");

    if (a2dpOk || hfpOk) {
        log::info("AirPods disconnect requested for {:012X} (A2DP={} HFP={})",
            m_address, a2dpOk ? "ok" : "fail", hfpOk ? "ok" : "fail");
        return true;
    }
    return false;
}

}
