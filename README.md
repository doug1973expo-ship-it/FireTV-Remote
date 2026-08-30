# Fire TV Remote

Android Wi‑Fi remote control for Amazon Fire TV. The app connects directly to a Fire TV through ADB over the local network.

## Setup

1. On Fire TV, enable **Settings → My Fire TV → Developer Options → ADB Debugging**.
2. Find its IP address under **Settings → My Fire TV → About → Network**.
3. Make sure the phone and Fire TV use the same Wi‑Fi.
4. Enter the address in the app, tap **Connect**, and approve the authorization prompt on the TV.

The default ADB port is 5555.

## Download the APK

Open **Actions**, select the latest **Build Android APK** run, and download the `firetv-remote-debug` artifact.

## Build locally

```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`
