#pragma once

#include <chrono>
#include <format>
#include <iostream>
#include <mutex>
#include <string>
#include <string_view>

namespace librepods::log {

inline std::mutex& mutex() {
    static std::mutex m;
    return m;
}

inline std::string timestamp() {
    using namespace std::chrono;
    const auto now = system_clock::now();
    return std::format("{:%H:%M:%S}", floor<milliseconds>(now));
}

template <class... Args>
void info(std::string_view fmt, Args&&... args) {
    std::scoped_lock lk{mutex()};
    std::cerr << "[" << timestamp() << "] INFO  "
              << std::vformat(fmt, std::make_format_args(args...))
              << '\n';
}

template <class... Args>
void warn(std::string_view fmt, Args&&... args) {
    std::scoped_lock lk{mutex()};
    std::cerr << "[" << timestamp() << "] WARN  "
              << std::vformat(fmt, std::make_format_args(args...))
              << '\n';
}

template <class... Args>
void error(std::string_view fmt, Args&&... args) {
    std::scoped_lock lk{mutex()};
    std::cerr << "[" << timestamp() << "] ERROR "
              << std::vformat(fmt, std::make_format_args(args...))
              << '\n';
}

template <class... Args>
void debug(std::string_view fmt, Args&&... args) {
#ifndef NDEBUG
    std::scoped_lock lk{mutex()};
    std::cerr << "[" << timestamp() << "] DEBUG "
              << std::vformat(fmt, std::make_format_args(args...))
              << '\n';
#else
    (void)fmt;
    ((void)args, ...);
#endif
}

}
