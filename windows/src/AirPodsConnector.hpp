#pragma once

#include <cstdint>
#include <mutex>

namespace librepods {

class AirPodsConnector {
public:
    explicit AirPodsConnector(std::uint64_t address);
    AirPodsConnector(const AirPodsConnector&) = delete;
    AirPodsConnector& operator=(const AirPodsConnector&) = delete;

    void setAddress(std::uint64_t address) { m_address = address; }
    std::uint64_t address() const { return m_address; }

    bool connect();
    bool disconnect();

    // Query Win32 Bluetooth API for the device's actual classic-Bluetooth
    // connection state (true = ACL link up to this PC right now).
    bool isClassicallyConnected();

    // True when at least one non-system audio session is in the Active state on
    // the AirPods render endpoint (i.e. a call, meeting, or other app is actively
    // using the AirPods for audio output right now).
    bool hasActiveAudioSessions();

    // Make the AirPods the default audio output (render) and input (capture) device
    // at all roles (Console/Multimedia/Communications). Polls for up to ~4 seconds
    // because audio endpoints register asynchronously after the ACL link comes up.
    bool setAsDefaultAudioDevice();

private:
    std::uint64_t m_address;
    std::mutex m_mutex;  // serialize connect/disconnect to avoid parallel races
};

}
