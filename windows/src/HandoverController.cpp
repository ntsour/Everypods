#include "HandoverController.hpp"

#include "Logger.hpp"
#include "crossdevice_protocol.hpp"

#include <array>
#include <thread>
#include <windows.h>

namespace librepods {

HandoverController::HandoverController(
    PeerRegistry& peers,
    AirPodsConnector& airpods,
    MediaPlaybackWatcher& media)
    : m_peers(peers), m_airpods(airpods), m_media(media)
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

        // Hysteresis: gain "active" instantly (we want peer-protection ASAP),
        // but require ≥2 consecutive idle polls (≥1 s at 500 ms cadence) before
        // dropping to "idle". A single-poll WASAPI/BT flicker during Teams route
        // renegotiation would otherwise tell Android "go ahead, grab the AirPods"
        // for a few hundred ms — long enough for Android's takeover to fire.
        bool lastActive = false;     // last state broadcast to peers
        int  idleStreak = 0;         // # consecutive polls observed idle while broadcast=active
        constexpr int kIdleStreakThreshold = 2;
        // Proactive-release tracking: set when broadcast flips to IDLE, cleared on
        // rising edge or when release has fired (one-shot).
        std::chrono::steady_clock::time_point idleSince{};
        constexpr auto kReleaseAfterIdle = std::chrono::seconds(15);
        while (m_watcherRunning.load()) {
            try {
                // Check if setState(LocalPc) signaled us to reset the idle timer.
                if (m_resetIdle.exchange(false)) {
                    idleSince = std::chrono::steady_clock::time_point{};
                }

                // Resolve Unknown state if peers are now connected.
                // Guard the packet send on setState's return value: if onPeerConnectionChanged
                // raced us here and already resolved Unknown, setState returns false and we
                // skip the duplicate send.
                if (m_state.load() == OwnershipState::Unknown && m_peers.isAnyConnected()) {
                    bool airpodsHere = m_airpods.isClassicallyConnected();
                    bool changed = setState(airpodsHere ? OwnershipState::LocalPc
                                                        : OwnershipState::RemoteAndroid);
                    if (changed) {
                        log::handover("OUT     {} → peer (periodic sync on Unknown state)",
                            airpodsHere ? "kAirPodsConnected" : "kAirPodsDisconnected");
                        m_peers.sendPacket(airpodsHere
                            ? crossdevice::kAirPodsConnected
                            : crossdevice::kAirPodsDisconnected);
                    }
                }

                // Only meaningful when AirPods are connected to this PC.
                bool active = m_airpods.isClassicallyConnected()
                           && m_airpods.hasActiveAudioSessions();

                if (active && !lastActive) {
                    // Rising edge: broadcast ACTIVE immediately.
                    lastActive = true;
                    idleStreak = 0;
                    idleSince  = std::chrono::steady_clock::time_point{};
                    log::handover("AUDIO   ACTIVE — AirPods have live audio sessions");
                    if (m_peers.isAnyConnected()) {
                        m_peers.sendPacket(crossdevice::kWindowsAudioActive);
                    }
                } else if (!active && lastActive) {
                    // Idle observed while we still consider ourselves active — count it.
                    ++idleStreak;
                    if (idleStreak >= kIdleStreakThreshold) {
                        // Sustained idle: broadcast IDLE and consider reclaim.
                        lastActive = false;
                        idleStreak = 0;
                        idleSince  = std::chrono::steady_clock::now();
                        log::handover("AUDIO   IDLE   — AirPods audio sessions gone");
                        if (m_peers.isAnyConnected()) {
                            m_peers.sendPacket(crossdevice::kWindowsAudioIdle);
                        }
                        // If AirPods were grabbed via Bluetooth while a call was active
                        // (bypassing the CrossDevice protocol), reclaim them immediately
                        // rather than waiting up to 20 s for TeamsCallWatcher to notice.
                        // We check m_state == LocalPc because an intentional release
                        // (onIncomingPacket → RequestDisconnect) sets state to RemoteAndroid
                        // before the BT disconnect propagates here.
                        if (m_state.load() == OwnershipState::LocalPc
                            && !m_airpods.isClassicallyConnected()) {
                            log::handover("RECLAIM AirPods grabbed via BT while call active — reclaiming");
                            onMediaPlayingChanged(true);
                        }
                    } else {
                        log::handover("AUDIO   transient blip — holding active state (streak {}/{})",
                                      idleStreak, kIdleStreakThreshold);
                    }
                } else if (active && lastActive) {
                    // Recovered before the threshold — reset the streak silently.
                    idleStreak = 0;
                } else {
                    // !active && !lastActive — steady idle. Proactive release after
                    // kReleaseAfterIdle so the phone can naturally take over without
                    // Windows reclaiming. Skips when state has already moved off LocalPc
                    // (a prior reclaim, peer-initiated handover, etc.).
                    if (idleSince != std::chrono::steady_clock::time_point{}
                        && m_state.load() == OwnershipState::LocalPc) {
                        auto now = std::chrono::steady_clock::now();
                        if (now - idleSince > kReleaseAfterIdle) {
                            auto secs = std::chrono::duration_cast<std::chrono::seconds>(
                                            now - idleSince).count();
                            log::handover("RELEASE Audio idle for {}s — releasing ownership to peer", secs);
                            setState(OwnershipState::RemoteAndroid);
                            m_airpods.disconnect();
                            // Capture timestamp AFTER disconnect() so m_lastLostOwnership
                            // reflects when the BT stack actually released, not when we
                            // decided to release. disconnect() can block 1-3 s on some
                            // drivers; a pre-disconnect timestamp would make the
                            // anti-pingpong window appear wider than it really is.
                            m_lastLostOwnership.store(tpToNs(std::chrono::steady_clock::now()));
                            if (m_peers.isAnyConnected()) {
                                log::handover("OUT     kAirPodsDisconnected → all peers (proactive release)");
                                m_peers.sendPacket(crossdevice::kAirPodsDisconnected);
                            }
                            idleSince = std::chrono::steady_clock::time_point{};  // one-shot
                        }
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

bool HandoverController::setState(OwnershipState s) {
    auto previous = m_state.exchange(s);
    if (previous != s) {
        const char* label = (s == OwnershipState::LocalPc)     ? "LocalPc"
                          : (s == OwnershipState::RemoteAndroid) ? "RemoteAndroid"
                          :                                        "Unknown";
        log::handover("STATE → {}", label);
        if (s == OwnershipState::LocalPc) {
            m_resetIdle.store(true);  // Signal watcher to clear proactive-release timer
        }
        if (m_onStateChanged) m_onStateChanged(s);
        return true;
    }
    return false;
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
        log::handover("SKIP    AirPods already on this PC — no action needed");
        return;
    }

    // Anti-pingpong: if we just lost ownership to Android, don't immediately take back.
    auto sinceLost = std::chrono::steady_clock::now() - tpFromNs(m_lastLostOwnership.load());
    if (sinceLost < std::chrono::milliseconds(3000)) {
        auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(sinceLost).count();
        log::handover("SKIP    Anti-pingpong: lost ownership {}ms ago — not reclaiming", ms);
        return;
    }

    const std::string appId = m_media.currentAppId();
    log::handover("TRIGGER Media/call active (app={}) — requesting handover from Android",
                  appId.empty() ? "unknown" : appId);
    // Pause local media so audio doesn't leak through PC speakers during the
    // ~1-2s while AirPods are migrating. We'll resume it once we've claimed them.
    const bool paused = m_media.tryPauseActive();

    if (m_peers.isAnyConnected()) {
        log::handover("OUT     kRequestDisconnect → all peers");
        m_peers.sendPacket(crossdevice::kRequestDisconnect);
    } else {
        log::handover("WARN    No peers connected — attempting BT connect without coordination");
    }

    // Dispatch all blocking work (sleep + BT connect + audio endpoint poll) to a
    // detached thread. The WinRT media-callback thread must not be blocked: the
    // runtime will terminate a callback thread that doesn't return promptly, causing
    // a crash.
    //
    // Connect retry backoff: 1.5 s, 3 s, 5 s. Xiaomi MIUI (and to a lesser extent
    // other Android vendors) acknowledges the kRequestDisconnect packet over RFCOMM
    // within ~300 ms but takes considerably longer to actually release the A2DP
    // profile — often 2-5 s. A single 1.5 s sleep + one connect attempt frequently
    // fails on Xiaomi and then the handover sits idle until TeamsCallWatcher's 20 s
    // per-PID dedup expires and re-fires. Retrying inside the thread cuts the worst
    // case from ~27 s to ~10-12 s.
    std::thread([this, paused]() {
        winrt::init_apartment(winrt::apartment_type::multi_threaded);
        constexpr std::array<int, 3> kBackoffsMs{1500, 3000, 5000};
        bool connected = false;
        for (size_t i = 0; i < kBackoffsMs.size(); ++i) {
            std::this_thread::sleep_for(std::chrono::milliseconds(kBackoffsMs[i]));
            log::handover("ACTION  BT connect attempt {}/{} (after {} ms wait)",
                          i + 1, kBackoffsMs.size(), kBackoffsMs[i]);
            if (m_airpods.connect()) {
                connected = true;
                break;
            }
            log::handover("RETRY   BT connect attempt {} failed — backing off",
                          i + 1);
        }
        if (connected) {
            setState(OwnershipState::LocalPc);
            m_lastLocalTakeover.store(tpToNs(std::chrono::steady_clock::now()));
            if (m_peers.isAnyConnected()) {
                log::handover("OUT     kAirPodsConnected → all peers");
                m_peers.sendPacket(crossdevice::kAirPodsConnected);
            }
            // Make AirPods the default audio render + capture device.
            m_airpods.setAsDefaultAudioDevice();

            // Resume whatever we paused above, now that AirPods are the active route.
            // Small delay lets the audio stack settle on the new endpoint first.
            if (paused) {
                std::this_thread::sleep_for(std::chrono::milliseconds(250));
                m_media.tryPlayActive();
            }

            // Deferred retry: device-management software (Jabra Direct, Poly Lens,
            // enterprise audio policies) often reasserts a managed headset as the
            // audio default a few seconds after a change. Re-assert AirPods after
            // 3 s to win that race. Also re-writes Teams' cmd_settings.json so any
            // call that the user answers in the interim picks up AirPods.
            std::this_thread::sleep_for(std::chrono::milliseconds(3000));
            if (m_airpods.isClassicallyConnected()) {
                log::handover("ACTION  Re-asserting AirPods as default audio (3s retry)");
                m_airpods.setAsDefaultAudioDevice();
            }
        } else {
            log::handover("FAIL    BT connect failed after {} attempts — AirPods not acquired",
                          kBackoffsMs.size());
            if (paused) {
                // Takeover failed — resume on whatever route we have so we don't leave
                // the user with paused media for no reason.
                m_media.tryPlayActive();
            }
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
            auto sinceTakeover = std::chrono::steady_clock::now() - tpFromNs(m_lastLocalTakeover.load());
            if (sinceTakeover < std::chrono::milliseconds(3000)) {
                auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(sinceTakeover).count();
                log::handover("IN      kRequestDisconnect — REJECTED (anti-pingpong, {}ms since takeover)", ms);
                if (m_peers.isAnyConnected()) {
                    m_peers.sendPacket(kAirPodsConnected);
                }
                break;
            }
            // Protect active audio sessions (Teams call, Zoom meeting, etc.).
            // The audio watcher already sent kWindowsAudioActive so Android should
            // have gated on takeoverWhenCall, but guard here too for races.
            if (m_airpods.isClassicallyConnected() && m_airpods.hasActiveAudioSessions()) {
                log::handover("IN      kRequestDisconnect — REJECTED (call/meeting active on Windows)");
                if (m_peers.isAnyConnected()) {
                    m_peers.sendPacket(kAirPodsConnected);  // re-assert
                }
                break;
            }
            log::handover("IN      kRequestDisconnect — ACCEPTED, releasing AirPods to Android");
            // Pause all Windows media before the AirPods leave so audio doesn't route
            // to PC speakers, and so the media-start callback doesn't fire and
            // immediately try to reclaim the AirPods.
            // Fire all three methods regardless of earlier successes: a GSMTC pause
            // of Spotify does not stop VLC (non-GSMTC), and the media key catches
            // anything else (browsers, etc.).
            m_media.tryPauseActive();        // GSMTC: pause the focused session
            m_media.tryPauseAllSessions();   // GSMTC: pause any background sessions
            m_media.tryPauseViaMediaKey();   // Media key: VLC, browsers, non-GSMTC apps
            m_airpods.disconnect();
            setState(OwnershipState::RemoteAndroid);
            m_lastLostOwnership.store(tpToNs(std::chrono::steady_clock::now()));
            if (m_peers.isAnyConnected()) {
                log::handover("OUT     kAirPodsDisconnected → all peers");
                m_peers.sendPacket(kAirPodsDisconnected);
            }
            break;
        }

        case Incoming::RequestHandover: {
            // Call-priority handover request from peer. Unlike RequestDisconnect,
            // this is never rejected — the peer is taking AirPods for a call, which
            // is peak user attention. Even mid-Teams-meeting on Windows, we yield.
            // The user's principle: calls always win, on whichever device they're on.
            log::handover("IN      kRequestHandover — ACCEPTED unconditionally (call priority)");
            m_media.tryPauseActive();
            m_media.tryPauseAllSessions();
            m_media.tryPauseViaMediaKey();
            m_airpods.disconnect();
            setState(OwnershipState::RemoteAndroid);
            m_lastLostOwnership.store(tpToNs(std::chrono::steady_clock::now()));
            if (m_peers.isAnyConnected()) {
                log::handover("OUT     kAirPodsDisconnected → all peers");
                m_peers.sendPacket(kAirPodsDisconnected);
            }
            break;
        }

        case Incoming::AirPodsConnected:
            log::handover("IN      kAirPodsConnected from peer → STATE RemoteAndroid");
            setState(OwnershipState::RemoteAndroid);
            break;

        case Incoming::AirPodsDisconnected:
            log::handover("IN      kAirPodsDisconnected from peer (waiting for local trigger)");
            // Don't claim ownership just because remote dropped — wait for our own media event.
            break;

        case Incoming::RequestConnectionStatus: {
            log::debug("Peer requested connection status");
            const auto& reply = (m_state.load() == OwnershipState::LocalPc)
                                ? kAirPodsConnected
                                : kAirPodsDisconnected;
            m_peers.sendPacket(reply);
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
        log::handover("PEER    Android connected via CrossDevice RFCOMM");
        // Re-sync cached state with reality before announcing.
        const bool airpodsHere = m_airpods.isClassicallyConnected();
        const bool wasLocalPc  = (m_state.load() == OwnershipState::LocalPc);

        setState(airpodsHere ? OwnershipState::LocalPc : OwnershipState::RemoteAndroid);
        log::handover("OUT     {} → peer (sync on connect)",
            airpodsHere ? "kAirPodsConnected" : "kAirPodsDisconnected");
        m_peers.sendPacket(airpodsHere
            ? crossdevice::kAirPodsConnected
            : crossdevice::kAirPodsDisconnected);

        // If we held ownership (LocalPc) but the AirPods are gone, the peer grabbed them
        // via Bluetooth before the RFCOMM coordination channel was established (bypassing
        // the protocol). Now that RFCOMM is up, reclaim them.
        if (wasLocalPc && !airpodsHere) {
            log::handover("RECLAIM Peer grabbed AirPods before RFCOMM was up — reclaiming now");
            onMediaPlayingChanged(true);
        }
    } else {
        log::handover("PEER    Android disconnected");
    }
}

}
