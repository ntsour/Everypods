# Heart rate when AirPods are put away

Both earbuds reported as charging or optimized charging in a fresh AACP battery packet from the current connection end monitoring and sharing normally. The first valid packet is sufficient; no lid transition is required. BLE charging flags, lid closure alone, and one stored or removed earbud do not confirm storage.

Storage cancels sensor and transport recovery, clears monitoring/sharing intent and ownership, clears readings, closes sharing transports, removes the sharing notification, and releases the sharing wake lock. The final sensor stop command is best effort: an already sleeping sensor must not turn normal storage into an error. Session generation guards reject already queued sensor callbacks.

Heart-rate details explain “Heart rate stopped — AirPods are in the case.” A fresh report that the earbuds are no longer both stored clears that explanation without starting monitoring. Fresh storage evidence also blocks an immediate restart. Case stops are system stops, not manual Gym automation overrides. Existing applicable Gym automation may start a new session later.

Unexpected interruptions retain the existing fixed 60-second recovery window. If no authoritative case packet arrives before the connection disappears, the service cannot reliably identify storage and continues to use bounded recovery. On a successful later connection, completed connection/no-data errors clear without restarting a session; active recovery and actionable ownership/transport failures remain intact.

Diagnostics identify Bluetooth disabled, missing device, closed or replaced control socket, sensor disconnect, sample timeout, recovery expiry, and confirmed case storage.

## Validation

Unit coverage checks first-packet storage, optimized charging, component order, partial storage, missing components, malformed/stale/previous-connection/out-of-order packets, failed final stop commands, storage during recovery or after expiry, rejected late samples, and reconnect/removal without automatic restart.

Physical checks still required on Pixel:

- Monitoring and each sharing method: insert both earbuds with the lid open, then close it. Confirm quiet stop, cleared receiver BPM, and removed sharing notification.
- Remove only one earbud, briefly remove both, and close an empty case. Confirm these alone do not end the session as case storage.
- Remove and reconnect before and after 60 seconds. Confirm no retained connection warning, no old BPM, and no automatic restart except an applicable Gym rule.
- Repeat storage/removal cycles and an unexpected Bluetooth outage while wearing the earbuds. Confirm recovery still works and no old callbacks revive a stopped session.
