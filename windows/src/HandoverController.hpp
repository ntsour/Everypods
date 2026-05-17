#pragma once

#include <atomic>
#include <chrono>
#include <functional>
#include <mutex>

#include "AirPodsConnector.hpp"
#include "BluetoothRfcommClient.hpp"
#include "MediaPlaybackWatcher.hpp"

namespace librepods {

enum class OwnershipState {
    Unknown,
    LocalPc,
    RemoteAndroid,
};

class HandoverController {
public:
    using StateChangedCallback = std::function<void(OwnershipState)>;

    HandoverController(BluetoothRfcommClient& rfcomm,
                       AirPodsConnector& airpods,
                       MediaPlaybackWatcher& media);

    void setOnStateChanged(StateChangedCallback cb) { m_onStateChanged = std::move(cb); }

    void onMediaPlayingChanged(bool playing);
    void onIncomingPacket(std::span<const std::uint8_t> data);
    void onPeerConnectionChanged(bool connected);

    OwnershipState state() const { return m_state.load(); }

private:
    void setState(OwnershipState s);
    bool withinDebounceWindow();

    BluetoothRfcommClient& m_rfcomm;
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

    StateChangedCallback m_onStateChanged;
};

}
