# EveryPods Versioning

EveryPods uses Semantic Versioning for its public version and a separate,
monotonically increasing Android version code for Google Play.

## Public Version

The version shown to users follows `MAJOR.MINOR.PATCH`:

| Change | Example | Use when |
| --- | --- | --- |
| Major | `2.0.0` | A substantial product, platform, or compatibility change. |
| Minor | `1.1.0` | New backwards-compatible features. |
| Patch | `1.0.1` | Bug fixes, small improvements, or security fixes. |

The first public EveryPods release is `1.0.0`.

## Android Version Code

`versionCode` is an internal Android and Google Play update counter. It is not
shown to users. Every upload to any Google Play track, including internal and
closed testing, must use a version code greater than every previously uploaded
build. A version code must never be reused.

EveryPods starts its public release history at code `100`. Increase it by one
for each Play upload: `101`, `102`, `103`, and so on.

## Updating a Release

Before creating a Play build, update these values in
`android/app/build.gradle.kts`:

```kotlin
val appVersionName = "1.0.1"

defaultConfig {
    versionCode = 101
    versionName = appVersionName
}
```

Build the Play bundle with:

```sh
cd android
./gradlew :app:bundlePlayRelease
```

The `playRelease` variant uses the plain public version, such as `1.0.0`.
Development builds retain the `-debug` suffix and must not be uploaded to
Google Play.
