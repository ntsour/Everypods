# Search scrolling regression — 18 October 2026

## Cause and fix

Search supplied the correct category and section anchor. `AnchorWrapper` recorded
`boundsInWindow().top`, but this rectangle is clipped to the viewport. Sections
below the viewport therefore registered at zero, and navigation stayed at the top.

The shared category helper now records the unclipped `positionInWindow().y`.
The scroll effect waits for the section's first layout measurement and runs once
per anchor. Position updates during the animation no longer cancel and restart it.
This fixes the shared behavior for all category section anchors.

## Pixel 10 validation

Release build `1.0.4-rc21-design2` (139) built successfully and installed via ADB.
Each search result below was selected in the installed app; the requested controls
were visible after automatic scrolling.

| Search | Destination observed |
| --- | --- |
| Sleep Timer | Smart features: timer heading and all five duration buttons |
| Sleep Timer Duration | Smart features: timer duration controls |
| Microphone | Audio: Microphone Settings and Microphone Mode |
| Press Speed | Controls: Press Speed options |
| Connect When Starting Media | Connection: Starting media playback toggle |
| App Language | App settings: language selector |
| Background Activity | App settings: Background activity section |
| App Version | Help: About section and installed version |
| Quiet Hours | Notification Announcements: Quiet hours controls |

The first eight cases exercise the shared category mechanism across all six
categories. Quiet Hours exercises the separate announcement-screen scrolling path.
Screenshots and visible accessibility bounds were inspected during these checks.

Validation command: `./gradlew -I /tmp/everypods-qa-version.init.gradle :app:assembleRelease`.
`git diff --check` also passed. Temporary navigation diagnostic logs were removed.
