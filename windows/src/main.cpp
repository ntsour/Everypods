#include <windows.h>

#include <winrt/base.h>
#include <winrt/Windows.Devices.Bluetooth.h>
#include <winrt/Windows.Devices.Bluetooth.Rfcomm.h>
#include <winrt/Windows.Devices.Enumeration.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.Storage.Streams.h>

#include "AirPodsConnector.hpp"
#include "BluetoothRfcommClient.hpp"
#include "HandoverController.hpp"
#include "Logger.hpp"
#include "MediaPlaybackWatcher.hpp"
#include "SettingsStore.hpp"
#include "TrayIcon.hpp"
#include "crossdevice_protocol.hpp"

#include <atomic>
#include <memory>
#include <optional>

using namespace winrt;
using namespace winrt::Windows::Devices::Bluetooth;
using namespace winrt::Windows::Devices::Bluetooth::Rfcomm;
using namespace winrt::Windows::Devices::Enumeration;

namespace librepods {

namespace {

std::wstring stateLabel(OwnershipState s) {
    switch (s) {
        case OwnershipState::LocalPc:       return L"AirPods: on this PC";
        case OwnershipState::RemoteAndroid: return L"AirPods: on Android";
        default:                            return L"AirPods: unknown";
    }
}

std::optional<std::uint64_t> discoverAndroidPeer() {
    try {
        winrt::guid rfcommGuid {
            0x1abbb9a4u, 0x10e4u, 0x4000u,
            { 0xa7, 0x5c, 0x89, 0x53, 0xc5, 0x47, 0x13, 0x42 }
        };
        auto rfcommId = RfcommServiceId::FromUuid(rfcommGuid);

        // Fast path: SDP cache already contains the UUID.
        auto selector = RfcommDeviceService::GetDeviceSelector(rfcommId);
        auto cachedHits = DeviceInformation::FindAllAsync(selector).get();
        if (cachedHits.Size() > 0) {
            auto service = RfcommDeviceService::FromIdAsync(cachedHits.GetAt(0).Id()).get();
            if (service && service.Device()) {
                auto bdev = service.Device();
                log::info("Discovered Android peer (cached SDP): {} ({})",
                    to_string(bdev.Name()),
                    SettingsStore::formatBluetoothAddress(bdev.BluetoothAddress()));
                return bdev.BluetoothAddress();
            }
        }

        // Slow path: enumerate paired classic-Bluetooth devices and query SDP uncached.
        log::info("SDP cache empty; scanning all paired Bluetooth devices for CrossDevice service...");
        auto pairedSelector = BluetoothDevice::GetDeviceSelectorFromPairingState(true);
        auto paired = DeviceInformation::FindAllAsync(pairedSelector).get();
        log::debug("Paired classic-Bluetooth devices: {}", paired.Size());

        for (auto&& info : paired) {
            try {
                auto bdev = BluetoothDevice::FromIdAsync(info.Id()).get();
                if (!bdev) continue;
                log::debug("  Probing {} ({})...",
                    to_string(bdev.Name()),
                    SettingsStore::formatBluetoothAddress(bdev.BluetoothAddress()));
                auto services = bdev.GetRfcommServicesForIdAsync(rfcommId, BluetoothCacheMode::Uncached).get();
                if (services.Services().Size() > 0) {
                    log::info("Discovered Android peer (uncached SDP): {} ({})",
                        to_string(bdev.Name()),
                        SettingsStore::formatBluetoothAddress(bdev.BluetoothAddress()));
                    return bdev.BluetoothAddress();
                }
            } catch (const hresult_error& e) {
                log::debug("    skipped (probe failed: {})", to_string(e.message()));
                continue;
            }
        }

        log::warn("No paired device advertises CrossDevice UUID. "
                  "Make sure: (1) the phone is paired with this PC, "
                  "(2) Handover is ON in the LibrePods Android app.");
        return std::nullopt;
    } catch (const hresult_error& e) {
        log::error("Peer discovery failed: 0x{:08X} {}",
            (std::uint32_t)e.code().value, to_string(e.message()));
        return std::nullopt;
    }
}

std::optional<std::uint64_t> discoverAirPods() {
    try {
        // Match paired audio devices whose name contains "AirPods".
        auto selector = BluetoothDevice::GetDeviceSelectorFromPairingState(true);
        auto devices = DeviceInformation::FindAllAsync(selector).get();
        for (auto&& info : devices) {
            auto name = std::wstring(info.Name());
            if (name.find(L"AirPods") != std::wstring::npos) {
                auto bdev = BluetoothDevice::FromIdAsync(info.Id()).get();
                if (bdev) {
                    log::info("Found paired AirPods: {} ({})",
                        to_string(bdev.Name()),
                        SettingsStore::formatBluetoothAddress(bdev.BluetoothAddress()));
                    return bdev.BluetoothAddress();
                }
            }
        }
        log::warn("No paired AirPods found. Pair them in Windows Settings first.");
        return std::nullopt;
    } catch (const hresult_error& e) {
        log::error("AirPods discovery failed: {}", to_string(e.message()));
        return std::nullopt;
    }
}

}

}

int APIENTRY wWinMain(HINSTANCE hInstance, HINSTANCE, PWSTR, int) {
    using namespace librepods;

#ifndef NDEBUG
    // Attach a console so stderr/stdout are visible when debugging.
    AllocConsole();
    FILE* dummy;
    freopen_s(&dummy, "CONOUT$", "w", stderr);
    freopen_s(&dummy, "CONOUT$", "w", stdout);
    SetConsoleTitleW(L"LibrePods Debug");
    log::info("=== LibrePods debug build ===");
#endif

    winrt::init_apartment(winrt::apartment_type::multi_threaded);

    SettingsStore store;
    Settings settings = store.load();

    if (!settings.androidAddress) {
        if (auto a = discoverAndroidPeer()) {
            settings.androidAddress = a;
            store.save(settings);
        }
    }
    if (!settings.airpodsAddress) {
        if (auto a = discoverAirPods()) {
            settings.airpodsAddress = a;
            store.save(settings);
        }
    }

    AirPodsConnector airpods{settings.airpodsAddress.value_or(0)};
    BluetoothRfcommClient rfcomm;
    HandoverController controller{rfcomm, airpods};
    MediaPlaybackWatcher media;
    TrayIcon tray;

    if (!tray.create(hInstance)) return 1;
    tray.setStatus(stateLabel(controller.state()));

    controller.setOnStateChanged([&tray](OwnershipState s) {
        tray.setStatus(stateLabel(s));
    });

    rfcomm.setOnPacket([&controller](std::span<const std::uint8_t> data) {
        controller.onIncomingPacket(data);
    });
    rfcomm.setOnState([&controller](bool connected) {
        controller.onPeerConnectionChanged(connected);
    });

    media.setCallback([&controller](bool playing) {
        controller.onMediaPlayingChanged(playing);
    });

    tray.setMenuHandler([&](int cmd) {
        switch (cmd) {
            case TrayIcon::kCmdQuit:
                tray.postQuit();
                break;
            case TrayIcon::kCmdPairAndroid:
                if (auto a = discoverAndroidPeer()) {
                    settings.androidAddress = a;
                    store.save(settings);
                    rfcomm.stop();
                    rfcomm.start(*a);
                }
                break;
            case TrayIcon::kCmdPairAirPods:
                if (auto a = discoverAirPods()) {
                    settings.airpodsAddress = a;
                    store.save(settings);
                    airpods.setAddress(*a);
                }
                break;
        }
    });

    if (settings.androidAddress) {
        rfcomm.start(*settings.androidAddress);
    } else {
        log::warn("No Android peer configured. Use tray menu \"Pair with Android...\" once it is paired in Windows.");
    }

    media.start();

    tray.runMessageLoop();

    media.stop();
    rfcomm.stop();
    return 0;
}
