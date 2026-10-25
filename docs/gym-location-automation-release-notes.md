# Gym location automation — privacy and release checklist

- Location permissions (`ACCESS_FINE/COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`) are requested only from the Gym automation editor, after an in-app prominent disclosure. Never at startup or from a geofence callback.
- The gym centre, radius and a small visit journal (presence, ownership/suppression flags, no coordinates) are stored in the app-private `gym_automation` preferences. `allowBackup=false` already applies. No location history, analytics or sync. Logs never contain coordinates.
- Detection is done by Android / Google Play services geofencing (`play-services-location` 21.4.0).
- **Play release (not done):** background location needs a Play Console permissions declaration, a demo video of the prominent disclosure and feature, and privacy-policy coverage. Approval isn't guaranteed for an optional feature. If a Play build ships without the feature, remove the background permission and the registration code together.
- The debug-only simulator receiver lives in `src/debug` and is not in release builds.
- Known limitations: one gym; no map picker; HR interrupted by process death is not auto-resumed (user resumes from the case art); the existing global “auto-share in Gym Mode” still uses the TICKR fallback for manual Gym Mode (unchanged); force-stop blocks callbacks until the app is reopened.
