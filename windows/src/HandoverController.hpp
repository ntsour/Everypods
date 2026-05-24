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
    void setState(OwnershipState s);
    bool withinDebounceWindow();
    void startAudioWatcher();

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
    std::chrono::steady_clock::time_point m_lastLocalTakeover{};

    // Sender-side anti-pingpong: when we lose ownership (Android takes over),
    // suppress our own media-start handover for 3 s in case Spotify/Chrome auto-resumes.
    std::chrono::steady_clock::time_point m_lastLostOwnership{};

    // Audio session watcher: background thread that polls for active audio on the
    // AirPods endpoint and signals state changes to connected Android peers.
    std::atomic<bool> m_watcherRunning{false};
    std::thread m_watcherThread;

    // Signaled by setState(LocalPc) to reset the proactive-release idle timer.
    std::atomic<bool> m_resetIdle{false};

    StateChangedCallback m_onStateChanged;
};

}
