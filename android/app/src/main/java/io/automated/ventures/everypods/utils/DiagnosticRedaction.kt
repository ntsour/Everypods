package io.automated.ventures.everypods.utils

/** Strip Bluetooth addresses, UUID-style ids and IPv4 addresses from text that may be shared. */
fun redactIdentifiers(value: String): String = value
    .replace(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}"), "[redacted-mac]")
    .replace(Regex("(?i)\\b[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}\\b"), "[redacted-id]")
    .replace(Regex("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])"), "[redacted-address]")
