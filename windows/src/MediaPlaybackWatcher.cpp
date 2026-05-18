#include <windows.h>  // for keybd_event, VK_MEDIA_PLAY_PAUSE
#include "MediaPlaybackWatcher.hpp"

#include "Logger.hpp"

using namespace winrt;
using namespace winrt::Windows::Foundation;
using namespace winrt::Windows::Media::Control;

namespace librepods {

namespace {
constexpr auto Playing = GlobalSystemMediaTransportControlsSessionPlaybackStatus::Playing;
}

MediaPlaybackWatcher::MediaPlaybackWatcher() = default;

MediaPlaybackWatcher::~MediaPlaybackWatcher() {
    stop();
}

void MediaPlaybackWatcher::start() {
    if (m_started.exchange(true)) return;
    try {
        m_manager = GlobalSystemMediaTransportControlsSessionManager::RequestAsync().get();
    } catch (const hresult_error& e) {
        log::error("Failed to get media session manager: {}", to_string(e.message()));
        m_started.store(false);
        return;
    }

    m_sessionsChangedToken = m_manager.SessionsChanged(
        [this](auto&&, auto&&) {
            rebuildSubscriptions();
            emitIfChanged();
        });

    rebuildSubscriptions();
    emitIfChanged();
}

void MediaPlaybackWatcher::stop() {
    if (!m_started.exchange(false)) return;
    if (m_manager) {
        try { m_manager.SessionsChanged(m_sessionsChangedToken); } catch (...) {}
    }
    std::scoped_lock lk{m_mutex};
    for (auto& [_, entry] : m_sessions) {
        try { entry.session.PlaybackInfoChanged(entry.playbackToken); } catch (...) {}
    }
    m_sessions.clear();
    m_manager = nullptr;
}

void MediaPlaybackWatcher::rebuildSubscriptions() {
    if (!m_manager) return;
    auto sessions = m_manager.GetSessions();

    std::scoped_lock lk{m_mutex};

    std::unordered_map<hstring, SessionEntry> next;

    for (auto&& session : sessions) {
        hstring id = session.SourceAppUserModelId();
        auto it = m_sessions.find(id);
        if (it != m_sessions.end()) {
            next.emplace(id, std::move(it->second));
            m_sessions.erase(it);
            continue;
        }
        SessionEntry entry;
        entry.session = session;
        bool isPlaying = false;
        try {
            isPlaying = session.GetPlaybackInfo().PlaybackStatus() == Playing;
        } catch (...) {}
        entry.playing = isPlaying;
        entry.playbackToken = session.PlaybackInfoChanged(
            [this, id](GlobalSystemMediaTransportControlsSession const& s, auto&&) {
                bool playing = false;
                GlobalSystemMediaTransportControlsSessionPlaybackStatus status{};
                try {
                    auto info = s.GetPlaybackInfo();
                    status = info.PlaybackStatus();
                    playing = status == Playing;
                } catch (...) {}
                log::debug("Session '{}' playback status -> {} (playing={})",
                    to_string(id), (int)status, playing);
                {
                    std::scoped_lock g{m_mutex};
                    auto it2 = m_sessions.find(id);
                    if (it2 != m_sessions.end()) it2->second.playing = playing;
                }
                emitIfChanged();
            });
        log::debug("Tracking media session: {} (initial playing={})", to_string(id), entry.playing);
        next.emplace(id, std::move(entry));
    }

    for (auto& [_, entry] : m_sessions) {
        try { entry.session.PlaybackInfoChanged(entry.playbackToken); } catch (...) {}
    }
    m_sessions = std::move(next);
}

bool MediaPlaybackWatcher::anyPlaying() const {
    for (const auto& [_, entry] : m_sessions) {
        if (entry.playing) return true;
    }
    return false;
}

bool MediaPlaybackWatcher::tryPauseActive() {
    if (!m_manager) return false;
    try {
        auto session = m_manager.GetCurrentSession();
        if (!session) {
            log::debug("tryPauseActive: no current session");
            return false;
        }
        auto status = session.GetPlaybackInfo().PlaybackStatus();
        if (status != Playing) {
            log::debug("tryPauseActive: current session not playing (status={})", (int)status);
            return false;
        }
        log::info("Pausing local media session: {}", to_string(session.SourceAppUserModelId()));
        bool ok = session.TryPauseAsync().get();
        if (!ok) log::warn("TryPauseAsync returned false (app may not support pause)");
        return ok;
    } catch (const hresult_error& e) {
        log::warn("tryPauseActive failed: 0x{:08X} {}",
            (std::uint32_t)e.code().value, to_string(e.message()));
        return false;
    }
}

bool MediaPlaybackWatcher::tryPlayActive() {
    if (!m_manager) return false;
    try {
        auto session = m_manager.GetCurrentSession();
        if (!session) {
            log::debug("tryPlayActive: no current session");
            return false;
        }
        auto status = session.GetPlaybackInfo().PlaybackStatus();
        if (status == Playing) {
            log::debug("tryPlayActive: current session already playing");
            return true;
        }
        log::info("Resuming local media session: {}", to_string(session.SourceAppUserModelId()));
        bool ok = session.TryPlayAsync().get();
        if (!ok) log::warn("TryPlayAsync returned false (app may not support play)");
        return ok;
    } catch (const hresult_error& e) {
        log::warn("tryPlayActive failed: 0x{:08X} {}",
            (std::uint32_t)e.code().value, to_string(e.message()));
        return false;
    }
}

bool MediaPlaybackWatcher::tryPauseAllSessions() {
    bool any_paused = false;
    {
        std::scoped_lock lk{m_mutex};
        for (auto& [id, entry] : m_sessions) {
            try {
                bool ok = entry.session.TryPauseAsync().get();
                if (ok) {
                    log::info("Paused session: {}", to_string(id));
                    any_paused = true;
                }
            } catch (const hresult_error& e) {
                log::debug("Failed to pause session {}: 0x{:08X}",
                    to_string(id), (std::uint32_t)e.code().value);
            } catch (...) {}
        }
    }
    return any_paused;
}

bool MediaPlaybackWatcher::tryPauseViaMediaKey() {
    // NOTE: Web browsers (Chrome, Edge) don't expose their media sessions to Windows APIs,
    // so we cannot reliably pause YouTube or other web players. This is a known limitation.
    // Regular media apps (Spotify, VLC, etc.) pause correctly via tryPauseActive/tryPauseAllSessions.
    log::info("Web player pause not supported (browser limitation)");
    return false;
}

void MediaPlaybackWatcher::emitIfChanged() {
    bool playing;
    {
        std::scoped_lock lk{m_mutex};
        playing = anyPlaying();
    }
    bool previous = m_lastPlayingEmitted.exchange(playing);
    if (previous != playing && m_callback) {
        log::info("Media playing -> {}", playing);
        m_callback(playing);
    }
}

}
