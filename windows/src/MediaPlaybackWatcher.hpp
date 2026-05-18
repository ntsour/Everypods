#pragma once

#include <atomic>
#include <functional>
#include <mutex>
#include <thread>
#include <unordered_map>

#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.Media.Control.h>

namespace librepods {

class MediaPlaybackWatcher {
public:
    using PlaybackCallback = std::function<void(bool playing)>;

    MediaPlaybackWatcher();
    ~MediaPlaybackWatcher();

    MediaPlaybackWatcher(const MediaPlaybackWatcher&) = delete;
    MediaPlaybackWatcher& operator=(const MediaPlaybackWatcher&) = delete;

    void setCallback(PlaybackCallback cb) { m_callback = std::move(cb); }

    void start();
    void stop();

    // Send transport commands to the OS's currently-focused media session. Used
    // by HandoverController to pause/resume local media around a handover so
    // audio doesn't briefly leak through PC speakers during the ACL switch.
    bool tryPauseActive();
    bool tryPlayActive();

    // Fallback pause: try all registered sessions (some web players don't expose
    // via GetCurrentSession). Returns true if any session was paused.
    bool tryPauseAllSessions();

    // Last-resort pause: send global media pause key. Works for any app that
    // respects media keys (most do). Returns true.
    bool tryPauseViaMediaKey();

private:
    void rebuildSubscriptions();
    bool anyPlaying() const;
    void emitIfChanged();

    winrt::Windows::Media::Control::GlobalSystemMediaTransportControlsSessionManager m_manager{nullptr};
    winrt::event_token m_sessionsChangedToken{};

    struct SessionEntry {
        winrt::Windows::Media::Control::GlobalSystemMediaTransportControlsSession session{nullptr};
        winrt::event_token playbackToken{};
        bool playing{false};
    };

    std::mutex m_mutex;
    std::unordered_map<winrt::hstring, SessionEntry> m_sessions;
    std::atomic<bool> m_lastPlayingEmitted{false};
    std::atomic<bool> m_started{false};

    PlaybackCallback m_callback;
};

}
