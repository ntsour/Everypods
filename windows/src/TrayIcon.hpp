#pragma once

#include <functional>
#include <string>

#include <windows.h>
#include <shellapi.h>

namespace librepods {

class TrayIcon {
public:
    using MenuHandler = std::function<void(int commandId)>;

    static constexpr int kCmdQuit = 1001;
    static constexpr int kCmdPairAndroid = 1002;
    static constexpr int kCmdPairAirPods = 1003;

    TrayIcon();
    ~TrayIcon();

    TrayIcon(const TrayIcon&) = delete;
    TrayIcon& operator=(const TrayIcon&) = delete;

    bool create(HINSTANCE hInstance);
    void setStatus(const std::wstring& text);
    void setMenuHandler(MenuHandler h) { m_menuHandler = std::move(h); }

    void runMessageLoop();
    void postQuit();

private:
    static LRESULT CALLBACK wndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp);
    LRESULT handleMessage(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp);
    void showContextMenu(HWND hwnd);

    HWND m_hwnd{};
    NOTIFYICONDATAW m_nid{};
    std::wstring m_statusText{L"LibrePods"};
    MenuHandler m_menuHandler;
    static constexpr UINT WM_TRAY = WM_APP + 1;
};

}
