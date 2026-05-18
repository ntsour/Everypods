#include "HandoverController.hpp"

#include "Logger.hpp"
#include "crossdevice_protocol.hpp"

#include <thread>

namespace librepods {

HandoverController::HandoverController(
    BluetoothRfcommClient& rfcomm,
    AirPodsConnector& airpods,
    MediaPlaybackWatcher& media)
    : m_rfcomm(rfcomm), m_airpods(airpods), m_media(media)
{
    startAudioWatcher();
}

HandoverController::~HandoverController() {
    m_watcherRunning.store(false);
    if (m_watcherThread.joinable()) m_watcherThread.join();
}

void HandoverController::startAudioWatcher() {
    m_watcherRunning.store(true);
    m_watcherThread = std::thread([this]() {
        try {
            winrt::init_apartment(winrt::apartment_type::multi_threaded);
        } catch (const std::exception& e) {
            log::warn("Audio watcher: failed to init WinRT apartment: {}", e.what());
            return;  // Exit thread, but don't crash the app
        }

        bool lastActive = false;
        while (m_watcherRunning.load()) {
            try {
                // Only meaningful when AirPods are connected to this PC.
                bool active = m_airpods.isClassicallyConnected()
                           && m_airpods.hasActiveAudioSessions();

                if (active != lastActive) {
                    lastActive = active;
                    log::info("Windows audio session: {}", active ? "ACTIVE (call/meeting)" : "IDLE");
                    // Notify all connected Android peers so they can gate takeover on the
                    // user's "takeover during call" preference instead of "takeover during music".
                    if (m_rfcomm.isConnected()) {
                        m_rfcomm.sendPacket(active ? crossdevice::kWindowsAudioActive
                                                   : crossdevice::kWindowsAudioIdle);
                    }
                }
            } catch (const std::exception& e) {
                log::warn("Audio watcher: exception during poll: {}", e.what());
                // Continue polling, don't crash
            }

            // Sliced sleep so shutdown is responsive (50 ms × 10 = 500 ms poll interval).
            for (int i = 0; i < 10 && m_watcherRunning.load(); ++i) {
                std::this_thread::sleep_for(std::chrono::milliseconds(50));
            }
        }
    });
}

void HandoverController::setState(OwnershipState s) {
    auto previous = m_state.exchange(s);
    if (previous != s && m_onStateChanged) m_onStateChanged(s);
}

bool HandoverController::withinDebounceWindow() {
    std::scoped_lock lk{m_debounceMutex};
    auto now = std::chrono::steady_clock::now();
    if (now - m_lastAction < kDebounce) return true;
    m_lastAction = now;
    return false;
}

void HandoverController::onMediaPlayingChanged(bool playing) {
    if (!playing) {
        log::debug("Media stopped — keeping current ownership");
        return;
    }
    if (withinDebounceWindow()) {
        log::debug("Debounced media-start event");
        return;
    }

    const bool actuallyConnected = m_airpods.isClassicallyConnected();
    const bool stateLocal = (m_state.load() == OwnershipState::LocalPc);
    log::debug("Media start: state={} airpodsConnected={}",
        stateLocal ? "LocalPc" : "remote/unknown",
        actuallyConnected);

    if (stateLocal && actuallyConnected) {
        log::info("Media started; AirPods already on this PC — refreshing audio routing only");
        m_airpods.setAsDefaultAudioDevice();
        return;
    }

    // Anti-pingpong: if we just lost ownership to Android, don't immediately take back.
    auto sinceLost = std::chrono::steady_clock::now() - m_lastLostOwnership;
    if (sinceLost < std::chrono::milliseconds(3000)) {
        auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(sinceLost).count();
        log::info("Media started but we just lost ownership {}ms ago — not taking back (anti-pingpong)", ms);
        return;
    }

    log::info("Media started on Windows; requesting handover from Android");

    // Pause local media so audio doesn't leak through PC speakers during the
    // ~1-2s while AirPods are migrating. We'll resume it once we've claimed them.
    const bool paused = m_media.tryPauseActive();

    if (m_rfcomm.isConnected()) {
        m_rfcomm.sendPacket(crossdevice::kRequestDisconnect);
    } else {
        log::warn("Peer not connected; cannot send REQUEST_DISCONNECT, proceeding to local connect anyway");
    }

    // Dispatch all blocking work (sleep + BT connect + audio endpoint poll) to a
    // detached thread. The WinRT media-callback thread must not be blocked: the
    // runtime will terminate a callback thread that doesn't return promptly, causing
    // a crash. 1500ms gives Android enough time to drop both AACP and A2DP.
    std::thread([this, paused]() {
        winrt::init_apartment(winrt::apartment_type::multi_threaded);
        std::this_thread::sleep_for(std::chrono::milliseconds(1500));
        if (m_airpods.connect()) {
            setState(OwnershipState::LocalPc);
            m_lastLocalTakeover = std::chrono::steady_clock::now();
            if (m_rfcomm.isConnected()) {
                m_rfcomm.sendPacket(crossdevice::kAirPodsConnected);
            }
            // Make AirPods the default audio render + capture device.
            m_airpods.setAsDefaultAudioDevice();

            // Resume whatever we paused above, now that AirPods are the active route.
            // Small delay lets the audio stack settle on the new endpoint first.
            if (paused) {
                std::this_thread::sleep_for(std::chrono::milliseconds(250));
                m_media.tryPlayActive();
            }
        } else if (paused) {
            // Takeover failed — resume on whatever route we have so we don't leave
            // the user with paused media for no reason.
            m_media.tryPlayActive();
        }
    }).detach();
}

void HandoverController::onIncomingPacket(std::span<const std::uint8_t> data) {
    using namespace crossdevice;
    auto kind = classify(data);
    switch (kind) {
        case Incoming::RequestDisconnect: {
            // Reject takeover attempts that happen within ~3s of our own takeover.
            // Android's MediaController fires takeover whenever media is "active",
            // which is often true right after we ourselves grabbed the AirPods.
            auto sinceTakeover = std::chrono::steady_clock::now() - m_lastLocalTakeover;
            if (sinceTakeover < std::chrono::milliseconds(3000)) {
                auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(sinceTakeover).count();
                log::info("Android requested handover {}ms after our takeover — ignoring (anti-pingpong)", ms);
                // Re-assert ownership so Android updates its view.
                if (m_rfcomm.isConnected()) {
                    m_rfcomm.sendPacket(kAirPodsConnected);
                }
                break;
            }
            // Protect active audio sessions (Teams call, Zoom meeting, etc.).
            // The audio watcher already sent kWindowsAudioActive so Android should
            // have gated on takeoverWhenCall, but guard here too for races.
            if (m_airpods.isClassicallyConnected() && m_airpods.hasActiveAudioSessions()) {
                log::info("Android requested handover but a call/meeting is active on Windows — rejecting");
                if (m_rfcomm.isConnected()) {
                    m_rfcomm.sendPacket(kAirPodsConnected);  // re-assert
                }
                break;
            }
            log::info("Android requested handover; disconnecting AirPods locally");
            // Pause local media before the AirPods leave so audio doesn't suddenly
            // route to (and play through) the PC speakers. The destination
            // (Android) will resume its own media on its end.
            // Try multiple strategies to handle different media players (browsers, apps, etc).
            if (!m_media.tryPauseActive()) {
                log::debug("Single-session pause failed; trying all sessions...");
                if (!m_media.tryPauseAllSessions()) {
                    log::debug("All-sessions pause failed; falling back to media key...");
                    m_media.tryPauseViaMediaKey();
                }
            }
            m_airpods.disconnect();
            setState(OwnershipState::RemoteAndroid);
            m_lastLostOwnership = std::chrono::steady_clock::now();
            if (m_rfcomm.isConnected()) {
                m_rfcomm.sendPacket(kAirPodsDisconnected);
            }
            break;
        }

        case Incoming::AirPodsConnected:
            log::debug("Peer reports AirPods connected on remote");
            setState(OwnershipState::RemoteAndroid);
            break;

        case Incoming::AirPodsDisconnected:
            log::debug("Peer reports AirPods disconnected on remote");
            // Don't claim ownership just because remote dropped — wait for our own media event.
            break;

        case Incoming::RequestConnectionStatus: {
            log::debug("Peer requested connection status");
            const auto& reply = (m_state.load() == OwnershipState::LocalPc)
                                ? kAirPodsConnected
                                : kAirPodsDisconnected;
            m_rfcomm.sendPacket(reply);
            break;
        }

        case Incoming::RequestBatteryBytes:
        case Incoming::RequestAncBytes:
            log::debug("Peer requested battery/ANC bytes — not supported in Windows v1");
            break;

        case Incoming::RelayHeader:
            log::debug("Peer relayed AACP packet — Windows v1 ignores AACP relay");
            break;

        case Incoming::WindowsAudioActive:
        case Incoming::WindowsAudioIdle:
            // These packets flow Windows → Android only; ignore if received from Android.
            log::debug("Ignoring audio-state packet from Android (not expected)");
            break;

        case Incoming::Unknown:
            log::debug("Unknown packet ({} bytes)", data.size());
            break;
    }
}

void HandoverController::onPeerConnectionChanged(bool connected) {
    if (connected) {
        log::info("Peer (Android) connected on CrossDevice channel");
        // Re-sync cached state with reality before announcing.
        const bool airpodsHere = m_airpods.isClassicallyConnected();
        setState(airpodsHere ? OwnershipState::LocalPc : OwnershipState::RemoteAndroid);
        m_rfcomm.sendPacket(airpodsHere
            ? crossdevice::kAirPodsConnected
            : crossdevice::kAirPodsDisconnected);
        log::debug("Announced ownership: AirPods {} here",
            airpodsHere ? "are" : "are NOT");
    } else {
        log::warn("Peer (Android) disconnected");
    }
}

}
