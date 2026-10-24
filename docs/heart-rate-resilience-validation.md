# Heart-rate resilience validation — 2026-10-24

Base: `origin/main` at `122d3e2` (merged PR #50). Working branch:
`codex/heart-rate-resilience`. The previous `codex/teams-mute-sync` checkout
was left intact. Unrelated untracked `camera-kit-comparator/` and
`tmp/relay-wt/` were not edited.

## Automated checks

Run from `android/` with Java 21:

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug --console=plain
python3 android/scripts/validate_localization.py
git diff --check
```

The final full debug unit-test run passed **351 tests, zero failures or
errors**, including the HR/BLE/DIRCON tests. Debug and minified release APK
builds passed after the connection-generation change. The 11-locale validation
and `git diff --check` passed. Final `:app:lintDebug` passed with 264 warnings
and one hint; the lint baseline filtered 18 errors, 212 warnings and seven
hints, and reported 33 baseline entries no longer present. Earlier lint runs
intermittently reported `SuspiciousIndentation` errors at inconsistent
locations (15, then 6); the final run passed without changing the baseline.
Recheck lint in CI because that tool behavior was not explained.

After clarifying that a later Start was manual, a subsequent user-confirmed
monitoring attempt left the HR notification active, but the Pixel logs captured
no further accepted HR samples through 18:01:26. They show companion-peer
reconnect backoff (`REMOTE_UNAVAILABLE` and `CONNECT_TIMEOUT`); these peer-link
events do not establish the AirPods sensor's cause. The follow-up build for the
TICKR delivery-label change passed the focused HR test filter, the full debug
unit-test suite (351 tests, zero failures or skips), `:app:assembleDebug`, and
`:app:lintDebug` with the same baseline totals. Localization validation and
`git diff --check` also passed.

The user later reported the Pixel screen still waiting/reconnecting. Logs then
showed a service-20 HR start command at 18:05:30 and accepted sensor sample
240 at 18:05:32; it is unknown whether that start was manual or automatic, and
the user had not reported seeing a BPM. At 18:05:55 the current debug APK was
installed in place on the Pixel with `adb install -r` (`Success`; version
`1.0.4-rc19-debug`, code 130; app data preserved). The app process and service
restarted, and there was no active HR notification or HR start in the new
process logs. This supports the process-restart no-auto-resume behavior, but
the updated build required a manual Start before monitoring resumed. On that
updated build, the Pixel showed Live. The user selected TICKR sharing; Wahoo on
the same Pixel listed TICKR as connected and its Record preview showed a
changing heart-rate value without starting a workout. After Stop, EveryPods
logged a successful zero-interval sensor command and `Session STOPPED`; Wahoo's
TICKR heart-rate field changed to `n/a`. This verifies actual Wahoo receipt and
stop clearing on the Pixel for this run. It does not establish Wahoo's behavior
after its process restarts or after a delayed/stale-sensor zero failure. A
second TICKR run exercised the in-session Reconnect action; after the next
fresh sample, Wahoo again showed a live TICKR value. Stopping that run repeated
the zero-interval command and returned Wahoo's field to `n/a`.

On the same updated Pixel build, standard BLE sharing was then started with
Wahoo on the Xiaomi and the Xiaomi's EveryPods process stopped. The Pixel UI
reported one receiver and one subscription, and its diagnostics showed 31
confirmed BLE notification completions. The Xiaomi display was asleep when
captured, so Wahoo's displayed BLE value on this updated build remains pending;
the earlier Pixel-to-Xiaomi Wahoo display check remains the prior-build result.

With that updated-build BLE session active, Xiaomi Bluetooth was turned off
for five seconds and then restored. The Pixel cleared its receiver/subscription
counts to zero, and confirmed sends remained at 68 during a further five-second
no-receiver interval. After Xiaomi Bluetooth returned, Wahoo resubscribed
without a new Pixel sensor-start command; counts returned to one receiver and
one subscription and confirmed sends rose to 82. The Xiaomi screen is locked,
so this run verifies receiver reconnection and fresh notification delivery but
not the updated-build Wahoo display value.

The current PR #50 checkout has no `testRelayPreviewUnitTest` or
`assembleRelayPreview` Gradle task. `testDebugUnitTest` covers the feature-gated
DIRCON code enabled in the debug build.

## Physical checks

After the user authorized an in-place install, `app-debug.apk` (version code
130, version `1.0.4-rc19-debug`) was installed with `adb install -r` on a Pixel
10 and Xiaomi 24091RPADG. Both installs reported `Success`. The existing Pixel
app and new debug APK had the same signing certificate; the signed release APK
had a different certificate, so it was not installed over the debug app. No app
data was cleared. The Xiaomi EveryPods process was force-stopped before using
that device as a receiver so its normal Bluetooth manager would not compete
for the AirPods.

With AirPods connected to the Pixel (reported firmware `9442752` in the HR
diagnostic log), the Home TICKR control started the sensor and showed a changing
live BPM. The HR notification was present. A 10-second Pixel Bluetooth off/on
interruption removed the displayed BPM, changed the notification to
reconnecting, then returned to fresh sensor samples and “Sending to Wahoo”
without a manual restart. Home Stop sent the zero-interval sensor command and
removed the HR notification. A late packet after Stop did not restart the
session. This verifies the app-side TICKR broadcast path, **not** that Wahoo
displayed the value or cleared it on Stop.

On the BLE method, the Pixel logged advertising of standard Heart Rate Service
`180D` and showed “Sensor Bluetooth visible.” Per the user's preference, Wahoo
on the Xiaomi was used as the receiver. It paired with “Pixel 10,” the Pixel
logged an HR notification subscription, and Wahoo's Record/data-source screen
showed live heart rate changing from 59 to 58 BPM. Turning only the Xiaomi's
Bluetooth off for 10 seconds removed its subscription; after turning it on,
the Pixel logged a new subscription and Wahoo showed a fresh 55 BPM. The Pixel
sensor session continued during this receiver-only interruption. Zwift had
briefly been opened before the user specified Wahoo, but it remained at
first-run onboarding, did not subscribe, and was closed.

An initial sender-side BLE Bluetooth-off check was interrupted while the Pixel adapter was off; the
Pixel then disappeared from wireless ADB and its prior ADB endpoint refused
reconnection. The user restored Bluetooth and USB ADB. At the next inspection,
the HR notification was absent and the HR screen said “Measurement stopped”
and “Connect audio to this phone before starting.” The exact time and cause
of that stop could not be established from retained logs, so this run cannot
prove BLE recovery or its 60-second expiry behavior. It did not silently
resume after the interruption.

A second sender-side BLE check used USB ADB. After a 10-second Pixel Bluetooth
off/on interval, the Pixel recreated HR service advertising and Wahoo
reconnected and resubscribed. AirPods audio did not return to the Pixel, so
no fresh sensor samples reached Wahoo. The app remained in reconnecting state
within the budget, then logged `Session ERROR reason=CONNECTION_LOST` and
removed the HR notification. Wahoo's heart-rate field displayed `--`. This
validates fail-closed behavior and BLE service recreation, but not successful
sensor recovery after sender Bluetooth off/on.

For monitor-only lifecycle, after manually returning AirPods audio to the
Pixel, the HR screen accepted a Start command. No valid sensor sample arrived
in that run. Pressing Home caused a zero-interval sensor command; returning to
the same HR destination within the grace period caused a new 1-second-interval
start command without an explicit Start tap. The screen kept BPM blank and
showed reconnecting. This establishes suspend/resume wiring, but fresh-sample
completion and grace expiry still need a run with valid sensor contact.

The completed checks cover BLE baseline and receiver-only interruption, and
partially cover sender Bluetooth interruption. After the AirPods case/reconnect
cycle, the Pixel reconnected on a refreshed ADB endpoint with Bluetooth on and
the AirPods active for A2DP. The user restarted monitoring; firmware service 84
acknowledged the start command. The parser rejected four packets with
`invalid_measurement_status`, then accepted fresh sensor data. That session
reached 120 valid samples by 17:51:38, with a live BPM on the Home heart-rate
control. This demonstrates roughly two minutes of sustained AirPods-to-Pixel
monitoring after the case/reconnection cycle. It did not exercise concurrent
Wahoo sharing; Wahoo BLE sharing on the Xiaomi was verified separately.

In a later monitoring attempt on 2026-10-24, the AirPods session produced four
`invalid_measurement_status` packets, then one valid sensor sample after a
manual Start tap at 17:59:19 (confirmed by the user). No further accepted sample
was present in the inspected log through 18:00:13; the peer connection entered
`Backoff` with `REMOTE_UNAVAILABLE` at 17:59:53. The HR notification showed
“Sending to Wahoo”, but no Wahoo display was observed for this attempt. This is
evidence of a subsequent dropout, not evidence of an automatic sensor retry or
continuous delivery. The cause of the peer loss is undetermined.

The final implementation tags socket-bound disconnect broadcasts with a
monotonically increasing connection generation. Regression tests verify that
an old generation cannot clear a replacement socket and that a disconnect
broadcast remains valid after its own socket was already cleared. Policy and
BLE adapter behavior also have automated coverage. There is still no
Robolectric service test that delivers actual Android adapter broadcasts
through `AirPodsService`, nor an end-to-end Activity lifecycle wiring test.

Remaining physical checks include Wahoo-displayed TICKR value/zero, same-phone
TICKR reconnect, repeated sender Bluetooth toggles and expiry, ownership
change, monitor background grace and rotation, process restart, Gym/method
switch, permission failure, notification Stop, and the 30-minute soak for each
transport. Automated checks cannot establish when another app clears its last
displayed BPM during sensor silence. Record Android/receiver versions and
timing for each completed row.

On the updated build, the Xiaomi receiver was then awakened and opened to
Wahoo Record preview. Wahoo identified `Pixel 10` as the heart-rate source and
displayed a live value, confirming that receiver-only Bluetooth reconnect
restored end-to-end BLE data. Stopping sharing from the Pixel's Heart Rate
screen caused Wahoo's heart-rate field to change to `n/a`. No workout was
started. This covers the updated-build receive, receiver reconnect, and
sender-stop clearing path; it does not establish clearing behavior in every
third-party receiver or during a sensor-only dropout.
