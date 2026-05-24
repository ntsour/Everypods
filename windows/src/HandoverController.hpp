#pragma once

#include <atomic>
#include <chrono>
#include <functional>
#include <mutex>
#include <thread>

#include "AirPodsConnector.hpp"
#include "MediaPlaybackWatcher.hpp"
#include "PeerRegistry.hpp"

namespace librepods {

enum class OwnershipState {
    Unknown,
    LocalPc,
    RemoteAndroid,
};

class HandoverController {
public:
    using StateChangedCallback = std::function<void(OwnershipState)>;

    HandoverController(PeerRegistry& peers,
                       AirPodsConnector& airpods,
                       MediaPlaybackWatcher& media);
    ~HandoverController();

    void setOnStateChanged(StateChangedCallback cb) { m_onStateChanged = std::move(cb); }

    void onMediaPlayingChanged(bool playing);
    void onIncomingPacket(std::span<const std::uint8_t> data);
    void onPeerConnectionChanged(bool connected);

    OwnershipState state() const { return m_state.load(); }

private:
    // Returns true if the state actually changed (the atomic exchange saw a different
    // previous value). Callers that need to act only on a real transition (e.g., send
    // a protocol packet) should gate on the return value to avoid duplicate sends when
    // two threads race to resolve the same Unknown→X transition.
    bool setState(OwnershipState s);
    bool withinDebounceWindow();
    void startAudioWatcher();

    // Helpers to store/load anti-pingpong timestamps atomically.
    // steady_clock::duration::count() is int64_t nanoseconds on all supported platforms.
    static std::int64_t tpToNs(std::chrono::steady_clock::time_point tp) noexcept {
        return tp.time_since_epoch().count();
    }
    static std::chrono::steady_clock::time_point tpFromNs(std::int64_t ns) noexcept {
        return std::chrono::steady_clock::time_point{
            std::chrono::steady_clock::duration{ns}};
    }

    PeerRegistry& m_peers;
    AirPodsConnector& m_airpods;
    MediaPlaybackWatcher& m_media;

    std::atomic<OwnershipState> m_state{OwnershipState::Unknown};

    std::mutex m_debounceMutex;
    std::chrono::steady_clock::time_point m_lastAction{};
    static constexpr std::chrono::milliseconds kDebounce{300};

    // Anti-ping-pong: timestamp of our last successful local takeover. We reject
    // incoming RequestDisconnect within ~3s of this to avoid Android's auto-takeover
    // logic stealing the AirPods right back.
    // Stored as nanoseconds (steady_clock::duration::count()) so the field can be
    // accessed atomically from both the watcher thread and WinRT/RFCOMM callbacks.
    std::atomic<std::int64_t> m_lastLocalTakeover{0};

    // Sender-side anti-pingpong: when we lose ownership (Android takes over),
    // suppress our own media-start handover for 3 s in case Spotify/Chrome auto-resumes.
    std::atomic<std::int64_t> m_lastLostOwnership{0};

    // Audio session watcher: background thread that polls for active audio on the
    // AirPods endpoint and signals state changes to connected Android peers.
    std::atomic<bool> m_watcherRunning{false};
    std::thread m_watcherThread;

    // Signaled by setState(LocalPc) to reset the proactive-release idle timer.
    std::atomic<bool> m_resetIdle{false};

    StateChangedCallback m_onStateChanged;
};

}
