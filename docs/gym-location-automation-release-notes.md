# Gym location automation — privacy and release checklist

- Location permissions (`ACCESS_FINE/COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`) are requested only from the Gym automation editor, after an in-app prominent disclosure. Never at startup or from a geofence callback.
- Up to 10 gym locations (name, centre, radius) and a small visit journal (presence, zone ids inside, ownership/suppression flags, no coordinates) are stored in the app-private `gym_automation` preferences. `allowBackup=false` already applies. No location history, analytics or sync. Logs never contain coordinates.
- Detection is done by Android / Google Play services geofencing (`play-services-location` 21.4.0).
- **Play release (not done):** background location needs a Play Console permissions declaration, a demo video of the prominent disclosure and feature, and privacy-policy coverage. Approval isn't guaranteed for an optional feature. If a Play build ships without the feature, remove the background permission and the registration code together.
- The debug-only simulator receiver lives in `src/debug` and is not in release builds.
- Known limitations: one gym; no map picker; HR interrupted by process death is not auto-resumed (user resumes from the case art); the existing global “auto-share in Gym Mode” still uses the TICKR fallback for manual Gym Mode (unchanged); force-stop blocks callbacks until the app is reopened.

## Map picker privacy (added with the map picker)

- Library: osmdroid 6.1.20 (Apache-2.0) with OpenStreetMap standard tiles. No API key,
  no billing account, no Google Maps SDK.
- Network: the app already declares `android.permission.INTERNET`; no new permission.
  Tiles are requested from `tile.openstreetmap.org` only while the user has the picker
  open on the Gym automation screen. The request reveals the viewed map area (tile
  x/y/zoom), the device IP and the User-Agent `EveryPods/<version> (Android; <package>)`.
  The picked point and the saved zones are never sent; they are stored only in the app's
  private preferences, as before.
- Geofencing, the visit state machine and background work make no network calls and do
  not load the map. Tile cache lives in the app cache dir (max 50 MB, trimmed to 40 MB),
  is cleared with the app cache, and needs no storage permission.
- Browsing and picking need no location permission; only "Use current location" does.
- Play Data safety: tile requests are not collection of user data by the app (no
  location or identifiers sent to EveryPods or third parties beyond what any HTTP fetch
  of public map tiles reveals). Review with the Data safety form before release: the
  OSMF tile server receives IP + viewed area; consider noting "approximate location may
  be inferred by the map tile provider from viewed tiles" in the privacy policy.
- Policy: OSMF Tile Usage Policy requires attribution (shown via CopyrightOverlay),
  a valid identifying User-Agent and no bulk/offline prefetching. If usage grows, move
  to a commercial/self-hosted tile source (osmdroid supports any XYZ source).
