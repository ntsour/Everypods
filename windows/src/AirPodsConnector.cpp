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

#include <chrono>
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

bool toggleProfile(std::uint64_t address, const GUID& profile, DWORD state, const char* label) {
    HANDLE hRadio = openFirstRadio();
    if (!hRadio) {
        log::error("No Bluetooth radio found");
        return false;
    }

    BLUETOOTH_DEVICE_INFO info = {};
    info.dwSize = sizeof(info);
    info.Address.ullLong = address;

    // Populate class-of-device and other fields from the system's paired-device record.
    DWORD getErr = BluetoothGetDeviceInfo(hRadio, &info);
    if (getErr != ERROR_SUCCESS) {
        log::warn("BluetoothGetDeviceInfo({:012X}) returned {} (0x{:08X}) — device may not be paired",
            address, getErr, getErr);
        CloseHandle(hRadio);
        return false;
    }
    log::debug("  device: connected={} class=0x{:08X}",
        info.fConnected ? "yes" : "no",
        info.ulClassofDevice);

    DWORD setErr = BluetoothSetServiceState(hRadio, &info, &profile, state);
    CloseHandle(hRadio);

    if (setErr != ERROR_SUCCESS) {
        log::warn("BluetoothSetServiceState({}, {}) returned {} (0x{:08X})",
            label, state == BLUETOOTH_SERVICE_ENABLE ? "ENABLE" : "DISABLE",
            setErr, setErr);
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

    // Open an RFCOMM socket to ANY service on the AirPods. This is the only way
    // I've found that reliably triggers Windows to fully integrate the device,
    // bring up audio profiles, and register the audio endpoint. BluetoothSet-
    // ServiceState alone returns error 87 even when fConnected=yes.
    bool socketOpened = false;
    try {
        auto services = device.GetRfcommServicesAsync(BluetoothCacheMode::Uncached).get();
        log::info("RFCOMM services discovered: {}", services.Services().Size());
        if (services.Services().Size() == 0) {
            log::warn("No RFCOMM services advertised; cannot force profile activation");
        }
        for (auto&& svc : services.Services()) {
            try {
                log::debug("  opening RFCOMM service '{}'...",
                    winrt::to_string(svc.ConnectionServiceName()));
                StreamSocket sock;
                sock.ConnectAsync(svc.ConnectionHostName(), svc.ConnectionServiceName()).get();
                socketOpened = true;
                log::info("RFCOMM socket opened — Windows should now activate audio profiles");
                // Close immediately; the act of opening is what triggers profile setup.
                sock.Close();
                break;
            } catch (const winrt::hresult_error& e) {
                log::debug("    open failed: 0x{:08X} {}",
                    (std::uint32_t)e.code().value, winrt::to_string(e.message()));
            }
        }
    } catch (const winrt::hresult_error& e) {
        log::warn("RFCOMM enumeration failed: 0x{:08X} {}",
            (std::uint32_t)e.code().value, winrt::to_string(e.message()));
    }

    // Belt-and-suspenders: also try BluetoothSetServiceState in case it works on
    // some firmware/OS combo where the socket path doesn't.
    toggleProfile(m_address, kA2dpSink,  BLUETOOTH_SERVICE_ENABLE, "A2DP");
    toggleProfile(m_address, kHandsFree, BLUETOOTH_SERVICE_ENABLE, "HFP");

    try {
        log::debug("  status after: connection={}", (int)device.ConnectionStatus());
    } catch (...) {}

    return socketOpened;
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

}

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

    // Endpoints register asynchronously after the ACL link comes up. Poll for ~4s.
    std::vector<Endpoint> renderHits, captureHits;
    for (int attempt = 0; attempt < 20; ++attempt) {
        renderHits  = findAirPodsEndpoints(enumerator.Get(), eRender);
        captureHits = findAirPodsEndpoints(enumerator.Get(), eCapture);
        if (!renderHits.empty() || !captureHits.empty()) break;
        log::debug("  AirPods endpoint not yet enumerated (attempt {}/20), waiting 200ms...",
                   attempt + 1);
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
    }

    if (renderHits.empty() && captureHits.empty()) {
        log::warn("No AirPods audio endpoint exists in Windows at all — the OS hasn't "
                  "registered them as an audio device. May need first-time setup in "
                  "Settings → Bluetooth.");
        if (weInited) CoUninitialize();
        return false;
    }

    auto setAll = [&](const Endpoint& ep, const char* label) {
        // If endpoint is hidden/unplugged/notpresent, try to make it visible first.
        if (ep.state != DEVICE_STATE_ACTIVE) {
            log::info("  {} endpoint is {} — making visible via SetEndpointVisibility",
                label, stateName(ep.state));
            HRESULT vr = policy->SetEndpointVisibility(ep.id.c_str(), 1);
            if (FAILED(vr)) {
                log::warn("  SetEndpointVisibility({}) failed: 0x{:08X}", label, (std::uint32_t)vr);
            }
        }
        for (ERole role : { eConsole, eMultimedia, eCommunications }) {
            HRESULT r = policy->SetDefaultEndpoint(ep.id.c_str(), role);
            if (SUCCEEDED(r)) {
                log::info("Set AirPods as default {} ({} role)",
                    label, role == eConsole ? "Console" :
                            role == eMultimedia ? "Multimedia" : "Communications");
            } else {
                log::warn("SetDefaultEndpoint({}, {}) failed: 0x{:08X}",
                    label,
                    role == eConsole ? "Console" :
                    role == eMultimedia ? "Multimedia" : "Communications",
                    (std::uint32_t)r);
            }
        }
    };

    for (auto& ep : renderHits)  setAll(ep, "render (output)");
    for (auto& ep : captureHits) setAll(ep, "capture (input)");

    if (weInited) CoUninitialize();
    return !renderHits.empty();
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
