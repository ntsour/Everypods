# Android localization

The default language is English in `app/src/main/res/values`. Translation
folders under `app/src/main/res/values-*` define the languages offered by the
app-language and speech-language pickers. Gradle generates the supported
language catalogue, Android per-app language configuration, and resource
filters from these folders; no Kotlin language registry needs editing.

To add a language, copy the English `strings.xml` and `speech.xml` into a new
Android locale folder (for example, `values-nl`), translate the values while
keeping resource names and format placeholders unchanged, then run:

```sh
python3 scripts/validate_localization.py
./gradlew :app:assembleDebug
```

`strings.xml` contains displayed text. `speech.xml` contains text composed by
EveryPods for spoken announcements. Android uses English for resources not yet
translated in a locale. External app notifications and user-provided names are
kept as supplied by their source.

The language selection uses Android's per-app locale preference, so a choice
made in EveryPods is reflected in Android Settings and vice versa. Speech
language remains a separate preference. System speech voices depend on the
voices installed on the device.
