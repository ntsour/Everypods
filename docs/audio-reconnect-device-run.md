# Reconnect device verification record

Status: **partially run; acceptance pending**. See `audio-reconnect-validation.md` for the exact observed cycles and failures. Do not mark acceptance passed based on build or installation.

Before installation, rediscover devices with `adb devices -l`; identify Pixel 10 and Xiaomi 24091RPADG without assuming prior wireless addresses. Check the installed EveryPods package, version and signing compatibility on each. Preserve pairing, app data, user settings and permissions. Install the same normal-ID debug APK with `adb -s <serial> install -r <apk>` only if signing and version permit an in-place update. Do not uninstall or clear data to work around a mismatch. Record the APK SHA-256, source revision, package/version and device serial mapping locally; verify the app starts on each phone.

For every cycle, record timestamp and timezone, last holder, starting Bluetooth/profile and case state, peer availability, screen state, action, expected and actual result, reconnect latency, and matching journal references from **both** phones. Export both journals before their 48-hour retention expires. Do not treat BLE observation cycles as physical openings or AACP connection as A2DP success. Record profile connection separately from actual audible route and observer notifications.

| Scenario | Minimum | Result / evidence |
|---|---:|---|
| Both peers online, each last holder | 10 cycles per phone | Pending |
| Other phone Bluetooth off, each last holder | 5 cycles per phone | Pending |
| Screen off after real idle | 5 cycles per phone | Pending |
| Close empty case after removal | 3 cycles per phone | Pending |
| Both buds stored | 3 cycles per phone | Pending |
| First profile attempt fails | Simulated; physical if reproducible | Pending |
| Peer link lost while peer retains audio | Simulated and controlled physical case | Pending |
| Concurrent playback, manual and call intents | Simulated matrix; physical where feasible | Pending |
| Bluetooth off/on, app process death and late proxy callback | Simulated and physical lifecycle checks | Pending |
| Playback handover, ear detection, calls, HR and relay | Smoke checks | Pending |

For process-death testing, preserve data and pairing. Start a controlled attempt, capture its journal and profile state, then use an approved non-destructive process stop; restart normally and observe whether the persisted fence reconciles. A fence created before the OS call may remain unresolved: record that as an availability failure, never clear app data to hide it. Test mixed app versions only with a reversible installation path and record whether a participant reservation clears after the older requester succeeds.

For the peer-assisted observation extension, install the same newly built APK on both phones only after rediscovery and signer/version checks. With both peer links synchronized, make Xiaomi the last holder, put both buds in the case, wait at least 15 seconds, open it and remove them without playback. Record whether Pixel sees a fresh identity-verified observation, whether the sender and receiver log `remote_case_observer` and `remote_case_receiver`, whether Xiaomi logs `remote_case_evidence`, reservation/grant/START, command dispatch and A2DP connected, and the elapsed time observed by the user. Collect both journals, including the absence of any event. Repeat with the roles reversed and with Xiaomi's scanner restricted if reproducible. Verify that a fresh stored observation produces `remote_case_withdrawn` and cancels a matching not-yet-dispatched episode; record when the OS command already started or a withdrawal did not arrive. Confirm audible routing and ask the user about unexpected sounds.

Exercise a peer without the extension using a reversible mixed-version install only: confirm its existing v2 connection remains usable, the extension attempt fails or times out without a frame on v2, and neither phone issues an audio command solely from a failed exchange. Also repeat both offline-last-holder directions; peer-assisted evidence is unavailable when the observer cannot communicate and it does not make a Xiaomi scan restriction disappear. The IRK-free limited mode cannot originate verified peer evidence. Record these cases as pending or failed when the required observation cannot be made.

Acceptance requires the plan's complete matrix and no unexplained failures in controlled online/offline case cycles. Record failures and latency distributions rather than inferring success from a limited sample. Device discovery must be repeated for each installation or capture.
