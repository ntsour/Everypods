# Contributing

1. Open an issue before starting a large change so the scope is clear.
2. Keep changes focused and preserve the GPLv3 notices in existing source.
3. Do not commit `local.properties`, signing keys, generated APK/AAB files,
   device logs, Bluetooth addresses, or credentials.
4. Run the focused tests and a debug build before opening a pull request:

```sh
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

5. Describe the Android version, AirPods model, and test conditions in the
   pull request when changing Bluetooth or device-specific behavior.

Pull requests must not add new analytics, network services, or collection of
personal data without updating `PRIVACY.md` and explaining the change.
