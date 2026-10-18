> Superseded interface and lifecycle: see [release candidate design and manual checks](heart-rate-sharing-release.md).
> TICKR now remains saved; stopping sends zero and does not request unpairing.
> The following sections preserve the earlier experiment and its historical results.

# Experimental same-phone Wahoo heart-rate prototype

## Standard BLE heart-rate peripheral

The Heart rate screen now has a separate **Start Bluetooth heart-rate sensor** mode. It hosts the Bluetooth SIG Heart Rate Service (180D), notifies Heart Rate Measurement (2A37) only after a client subscribes, reports Body Sensor Location as Other, and includes Device Information service strings. Android BLE advertise/connect permissions are requested for this explicit session. The service reports connected clients, subscriptions, and successful notification sends in the UI.

The important unresolved check is Wahoo's ability to discover and connect to a BLE peripheral hosted by the same Pixel that runs its scanner. Android supports central and peripheral modes, but that does not establish same-adapter self-discovery. Wahoo may list the peripheral using the Pixel's Bluetooth name. No Wahoo workout should be recorded during this discovery experiment.

## Wahoo Android diagnostic input

The Heart rate screen also offers **Start Wahoo app diagnostic**. It uses Wahoo 7.2.0's exported, undocumented diagnostic broadcasts, sending only valid live AirPods readings and setting the initial reading before pairing the temporary `TICKR` sensor. This path is for a local test and does not use the Wi-Fi DIRCON server.

Wahoo retained the temporary sensor after a prior relay-based unpair attempt. EveryPods sets the diagnostic reading to zero and requests unpair on Stop. Do not start a workout during this experiment. If TICKR remains under **Sensors → My sensors**, remove it with **Forget sensor → Forget**; Wahoo can retain a last value while the temporary sensor remains paired.

### Same-Pixel app-to-app check — 2026-10-17

On Pixel 10 with Wahoo 7.2.0, Wahoo's receiver logged the EveryPods app's `SET_SIM_VALUE` and `PAIR_SIM` broadcasts while real AirPods samples were arriving. The user confirmed that Wahoo displayed a live BPM. After Stop, logs show the zero-value and targeted-unpair broadcasts; a read-only screenshot of Wahoo's My devices page showed the original four sensors and no TICKR. This confirms the native app-to-app diagnostic path and cleanup in this run. The numeric BPM was not captured for a sample-by-sample comparison, and one successful cleanup does not establish cleanup behavior after process death or network/device loss.

## Design

EveryPods reads real AirPods Pro 3 BPM through its existing AACP connection and
publishes a DIRCON heart-rate endpoint on the current Wi-Fi IPv4 interface.
The endpoint advertises `_wahoo-fitness-tnp._tcp` using Android NsdManager,
service 180D and notify characteristic 2A37. It serves discovery, characteristic
metadata, subscription, keepalive and fresh unsigned BPM notifications over TCP.
Wire framing follows QZ's DIRCON implementation (GPL-3.0, attribution retained).
No trainer/power data, synthetic HR or Wahoo cloud credentials are required.

The HRM screen offers **Start sharing to Wahoo** as an explicit opt-in. Sharing
starts sensor monitoring and a persistent Stop notification. Only this mode
survives leaving the screen/background; ordinary Start retains foreground-only
behavior. Stop, sensor timeout/disconnect, ownership/device change, service
shutdown or Wi-Fi change ends sharing and clears the displayed reading. No
session restarts automatically. Stale samples are never replayed to new clients.
Local-network access permission is requested on Android 17+ before starting.
Sharing exposes HR to devices on the same Wi-Fi while enabled; the UI explains
this and shows connected/subscribed client and sent-packet counts. Each client
has bounded buffers and closes on invalid/oversized requests or queue overflow.

## Prototype acceptance

1. Build/install a signed data-preserving update on Pixel.
2. Verify NSD advertisement and exact TCP discovery/subscription wire behavior.
3. Open the installed Wahoo Android app on the same Pixel and try sensor pairing.
4. Distinguish advertisement, TCP connection, enumeration, subscription and actual
   notification/display results. Success requires Wahoo's BPM matching real
   incoming EveryPods readings, not merely an advertisement or simulated client.
5. Check Stop, screen lock/background, stale data, Wi-Fi loss and reconnect.
6. Preserve the existing HRM, audio, EQ, gesture and handover implementation.

Initial validation requires Wi-Fi. Cellular-only/outdoor discovery is outside
this initial prototype; same-host and standalone-HRM support in Wahoo remain
unproven until observed. Do not claim that generic Wahoo Direct Connect support
proves either. Keep captures local, outside Git. No commit/push unless requested.

## References

- https://www.wahoofitness.com/devices/indoor-cycling/accessories/kickr-dircon-buy
- https://github.com/cagnulein/qdomyos-zwift/tree/master/src/devices/dircon
- https://developer.android.com/privacy-and-security/local-network-permission
