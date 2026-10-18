# Third-Party Notices

## Apple product assets

The Android source currently includes AirPods product artwork and the SF Pro
font in `android/app/src/main/res-apple`. These assets are identified here for
transparency and are not relicensed by the EveryPods GPLv3 license. Apple owns
the relevant trademarks and asset rights. Verify the right to redistribute
these assets before publishing binaries or source publicly.

EveryPods is not affiliated with or endorsed by Apple.

## Open-source dependencies

The Android application includes open-source dependencies whose notices and
licenses are displayed in the in-app Open Source Licenses screen. Dependency
updates must preserve those notices.

## libopus (relay-core, debug-only Wi-Fi audio relay)

`android/relay-core` builds libopus 1.5.2 from the official xiph.org source tarball
(SHA-256 pinned in `relay-core/src/main/cpp/CMakeLists.txt`). libopus is
Copyright (c) Xiph.Org Foundation, Skype Limited, Octasic, Jean-Marc Valin,
Timothy B. Terriberry, CSIRO, Gregory Maxwell, Mark Borgerding, Erik de Castro
Lopo and others, and is licensed under the BSD 3-Clause License (see `COPYING`
in the libopus distribution). BSD-3-Clause is compatible with this project's
GPL-3.0-or-later license. Before any release that ships relay-core, the libopus
notice must also appear in the in-app Open Source Licenses screen.
