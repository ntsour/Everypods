#pragma once

#include <array>
#include <cstdint>
#include <cstring>
#include <span>
#include <string_view>

namespace librepods::crossdevice {

inline constexpr std::string_view kServiceUuid =
    "1abbb9a4-10e4-4000-a75c-8953c5471342";

using Packet4 = std::array<std::uint8_t, 4>;

inline constexpr Packet4 kAirPodsConnected      { 0x00, 0x01, 0x00, 0x01 };
inline constexpr Packet4 kAirPodsDisconnected   { 0x00, 0x01, 0x00, 0x00 };
inline constexpr Packet4 kRequestDisconnect     { 0x00, 0x02, 0x00, 0x00 };
inline constexpr Packet4 kRequestBatteryBytes   { 0x00, 0x02, 0x00, 0x01 };
inline constexpr Packet4 kRequestAncBytes       { 0x00, 0x02, 0x00, 0x02 };
inline constexpr Packet4 kRequestConnectionStat { 0x00, 0x02, 0x00, 0x03 };
inline constexpr Packet4 kAirPodsDataHeader     { 0x00, 0x04, 0x00, 0x01 };

enum class Incoming {
    Unknown,
    AirPodsConnected,
    AirPodsDisconnected,
    RequestDisconnect,
    RequestBatteryBytes,
    RequestAncBytes,
    RequestConnectionStatus,
    RelayHeader,
};

inline bool equals4(std::span<const std::uint8_t> data, const Packet4& p) {
    return data.size() == 4 && std::memcmp(data.data(), p.data(), 4) == 0;
}

inline bool startsWith4(std::span<const std::uint8_t> data, const Packet4& p) {
    return data.size() >= 4 && std::memcmp(data.data(), p.data(), 4) == 0;
}

inline Incoming classify(std::span<const std::uint8_t> data) {
    if (equals4(data, kRequestDisconnect))        return Incoming::RequestDisconnect;
    if (equals4(data, kAirPodsConnected))         return Incoming::AirPodsConnected;
    if (equals4(data, kAirPodsDisconnected))      return Incoming::AirPodsDisconnected;
    if (equals4(data, kRequestBatteryBytes))      return Incoming::RequestBatteryBytes;
    if (equals4(data, kRequestAncBytes))          return Incoming::RequestAncBytes;
    if (equals4(data, kRequestConnectionStat))    return Incoming::RequestConnectionStatus;
    if (startsWith4(data, kAirPodsDataHeader))    return Incoming::RelayHeader;
    return Incoming::Unknown;
}

}
