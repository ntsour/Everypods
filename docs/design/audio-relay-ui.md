# Audio relay UI specification

Updated 19 October 2026 to reflect the accepted connections-list design. This specification supersedes the former selected-mode screen and hidden device-list entry. The earlier HTML fragment is a historical mockup; production behavior follows this document and the [Android UI contract](android-ui-contract.md).

## Saved connections

The canonical setup destination is **Connection → Connections** (`category/connection?anchor=connections`). The disconnected Home also embeds the same connection list. Old connected-device routes and search resolve to this group.

Each row represents a device identity and one connection type, with the discovered name, actual online/offline reachability, type, and an accessible minus action. A device with both types has two rows. Offline rows stay visible. A reachable peer is not necessarily streaming.

**Add connection → select device → select one type → complete setup.** Existing Bluetooth-bonded Android devices appear in the picker; unknown devices must first be bonded in Android settings. Choosing an already saved type opens its details. New Wi-Fi connections do not implicitly create BT handover entries.

- **BT handover:** configured locally. Row details expose status and manual recovery/handover. Removing it affects only this device's BT handover configuration.
- **Wi-Fi relay:** verified on both endpoints by matching the comparison code. The shared authorization works in either direction; it has no permanent sender/receiver role. Removing it disables local relay immediately, ends any affected session, and sends an authenticated revocation to the peer. Offline revocations remain pending until acknowledged. The other connection type and Android Bluetooth bond survive.

Trust identities remain separate from audio-relay authorization so revocation can be delivered without deleting BT coordination. Existing legacy handover peers and verified relay identities migrate without new verification. Removed connections never reappear through repeated migration.

## Everyday controls

Home shows **Audio transmission** immediately below connection status. Every saved Wi-Fi peer appears directly as a full-width button with supporting availability text. There is no intermediate Devices button.

- Eligible peer: **Play through [name]**, with AirPods connected there.
- Offline or no AirPods: keep the peer visible and disable outgoing Start, explaining the actual reason.
- Verification incomplete: show the saved connection as unavailable; complete verification in Connection settings.
- Local receiver: **Ready to receive audio** or **Receiving from [sender]**, with Stop while active. Peer buttons open details and never imply remotely granting capture consent.
- Active sender: **Sending to [receiver] · Stop** and actual playback health. Pending switch requests expose Cancel request.
- Occupied receiver: **[receiver] is receiving from [sender]** and Request switch. Keep receiver approval, expiry and single-stream arbitration.

Status opens the scrollable session sheet. The strip on other screens, notification and Quick Settings tile consume service state. Notification taps open the relevant peer. Sender Start negotiates a route before Android capture consent; no app picker is added.

## Connection lifetime and handover

The AirPods Bluetooth owner is the receiver; the initiating peer is the sender. The hotspot provider is independent of both roles. Names and capability checks must work for arbitrary supported Android phones/tablets.

BT handover and Wi-Fi relay can coexist as saved connections. Automatic media/call handover on the sender is suppressed from explicit relay Start through setup, approval waiting, playback and buffering. Stop, cancellation or failure releases that temporary suppression without rewriting saved settings. A Wi-Fi entry alone does not suppress an independently configured BT handover connection. Explicit handover confirms the consequence, stops relay, then invokes the existing handover action.

Wi-Fi coordination stays available when the global BT handover toggle is off. Per-endpoint Allow incoming audio remains independent of symmetric verification. Bluetooth signaling/identity verification do not permanently grant Android capture permission.

## Layout and validation

Use existing SF Pro typography and one 18dp rounded group surface. At regular text size and sufficient width, rows show name, status, type and minus on one line. Long names, narrower windows and large text use stacked name/status/type with a minimum 48dp minus target. Text expands naturally; all new labels, explanations and accessibility descriptions are localized. The session sheet scrolls in landscape and at large font sizes.

Verify migration, both saved types for one peer, duplicate prevention, independent removal, acknowledged offline revocation, symmetric setup and automatic-handover restoration. Exercise offline/no-AirPods/unverified/occupied/pending/streaming/buffering/failure states. Keep codec, buffering and negotiated network behavior unchanged. User listening and interaction checks remain separate from successful tests, builds and installs; arbitration needs three compatible devices for physical acceptance.

### Expandable settings and Wi-Fi session actions

Inline expandable settings always show a visible down/up chevron, whether collapsed or expanded. Use the shared `SettingsExpansionHeader` with wrapping labels, a minimum 48dp touch target, and localized expanded/collapsed accessibility state. Experimental audio settings use this header.

The Wi-Fi connection screen offers Stop as a full-width session action. Do not place a Move AirPods here button beside it; Bluetooth transfer remains in the Bluetooth connection flow.

### Compact active session on Home

On both participants, the active Home card shows only “Sending to [receiver]” or “Receiving from [sender]” and a separate full-width Stop action. Do not add a generic playback-health line, duplicate transmission heading or tappable card that opens a session popup. Idle senders retain their direct receiver list. Pending switch approval and actionable suspension/Resume information remain visible when required. Detailed configuration and session statistics remain under Connection settings.
