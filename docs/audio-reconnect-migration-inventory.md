# A2DP migration inventory

Baseline: origin/main `089548e78a1786d99d2e5b50c71e6bfb57b800c8`, 31 October 2026. This is an inventory, not an implementation or validation.

| Path | Current source | Migration requirement |
|---|---|---|
| Lid and case events | `AirPodsService` BLE listener (`maybeScheduleLidAutoconnect`), battery case callback | Submit timestamped passive evidence; unify episode and retry ownership |
| Ear-in | `AirPodsService` ear detection (~3042) | Submit passive intent; keep case and peer safeguards |
| Peer acquisition | `AirPodsService` ready listener (~1805), `CrossDevice.observePassiveA2dpState` (~5084) | Route grant and observed outcome to audio coordinator; retain unresolved reservation protection |
| Playback | `MediaController` (~639), `AirPodsService.takeOver` (~5273) | Submit explicit media intent, preserve replay handling and takeover fence |
| Incoming/active calls | `AirPodsService` call callbacks (~1834–1904) | Submit call intent and preserve active-call protection |
| Manual reconnect | `AirPodsService` (~5207, ~6883) | Submit explicit manual intent and prevent stale delayed actions |
| Audio connect adapter | `AirPodsService.connectAudio` (~6367) | Encapsulate OS command/proxy callbacks; one active command authority |
| Audio disconnect | `AirPodsService.disconnectAudio` (~6305), battery/ownership/ear callbacks (~2217–3062) | Route explicit release through coordinator; do not release a newer attempt |
| Passive retry | `startLidConnectWithRetries` (~7399), `scheduleLidA2dpOutcomeWatch` | Replace fixed early fan-out with profile-outcome-driven bounded recovery |
| Takeover retry | `takeOver` retries (~5426), `TakeoverAttemptFence` | Coordinate retry/ownership with other triggers; retain stale-callback fencing |
| A2DP profile broadcasts | connection receiver (~5078) | Submit per-headset observed transitions; reconcile delayed/untagged callbacks |
| Control channel | `bluetooth/connection/ConnectionEngine` | Keep separate; AACP connectivity is not evidence of an audio route |

The checkout includes the plan and design copied from the user's original working tree. This baseline retains independent audio entry points, and no coordinator migration has yet been implemented.

## Current migration status (validation in progress)

All current A2DP `connectAudio` calls are submitted via the audio driver. Profile query/connection reflection moved to `AndroidAudioProfileBackend`; A2DP and HEADSET release reflection and secondary HEADSET connection remain in the service, fenced by the driver. Existing passive scheduling and outcome-observation code remains and requires further audit/cleanup; this inventory's baseline descriptions above are not evidence of final single-authority completion. The UI now observes the stored policy through `AudioLeasePrefs`, while service code reads the same preferences. A full observable policy repository and complete write-origin coverage remain to be established.

Case-open preference writes found in the app consist of initialization and the settings UI; both now call `AudioLeasePrefs`. Relay and peer setup have no direct writes to this key in the current source. The policy source reconciles unknown external changes without attributing an origin beyond `external_refresh`; it cannot reconstruct intermediate writes that occurred while the process was absent.
