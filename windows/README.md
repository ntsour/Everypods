# LibrePods — Windows

Minimal Windows companion for LibrePods. Implements cross-device handover only:
when you start media on Windows, the AirPods migrate from your paired Android
device; when Android needs them back (incoming call, media, etc.), they migrate
back. No ANC / battery / ear-detection on Windows — those live on Android.

## Requirements
- Windows 10 1803 or newer (Windows 11 recommended)
- Bluetooth + classic-RFCOMM-capable adapter
- Visual Studio 2022 Build Tools (or full IDE) with the "Desktop development with C++" workload and Windows 10/11 SDK
- CMake 3.20+

## Build
```powershell
cd windows
cmake -S . -B build -G "Visual Studio 17 2022" -A x64
cmake --build build --config Release
```
The resulting binary is `build/Release/LibrePods.exe`.

## First-time setup
1. Pair AirPods with Windows (Settings → Bluetooth & devices → Add).
2. Pair your Android phone with Windows (same place).
3. Enable Cross-Device in the LibrePods Android app.
4. Run `LibrePods.exe`. The tray icon appears. On first launch it auto-discovers
   both peers and writes `%APPDATA%\LibrePods\config.txt`.
5. If discovery fails: right-click tray → *Pair with Android...* and *Select AirPods...*

## How it works
LibrePods talks to Android's existing `1abbb9a4-10e4-…-5471342` RFCOMM service
using the 4-byte `CrossDevicePackets` protocol (see
[CrossDevice.kt](../android/app/src/main/java/io/nikos/propods/utils/CrossDevice.kt)).
Windows does not speak AACP/L2CAP to the AirPods themselves — it relies on the
OS A2DP/HFP profiles, so the AirPods appear as a normal Bluetooth headset.

| Direction | Packet | Behavior |
| --- | --- | --- |
| Windows → Android | `00 02 00 00` | Windows started media, please release AirPods |
| Android → Windows | `00 02 00 00` | Android needs them back, disconnect locally |
| Either way | `00 01 00 01` / `00 01 00 00` | Ownership status broadcast |

## Limitations (deferred)
- Battery / ANC / ear-detection UI
- Auto-start on login (registry entry)
- MSI installer + code signing
- Multi-peer disambiguation when several CrossDevice servers are paired
