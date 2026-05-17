#pragma once

#include <cstdint>
#include <filesystem>
#include <optional>
#include <string>

namespace librepods {

struct Settings {
    std::optional<std::uint64_t> androidAddress;
    std::optional<std::uint64_t> airpodsAddress;
};

class SettingsStore {
public:
    SettingsStore();
    Settings load() const;
    void save(const Settings& s) const;

    static std::optional<std::uint64_t> parseBluetoothAddress(std::string_view s);
    static std::string formatBluetoothAddress(std::uint64_t addr);

private:
    std::filesystem::path m_path;
};

}
