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
