# VillaCam

<img src="assets/branding/villacam-icon-preview.png" alt="VillaCam icon" width="120" />

**An Android camera for school television and live production.**

Created by **Efren C.D.** for Villavision, the school television project at Villaverde.

VillaCam turns an Android phone into a wireless camera for OBS Studio. **SRT is the default**; an optional mode embeds the official **VDO.Ninja / WebRTC** publisher.

Current version: **0.13.0**. The native interface automatically uses **Spanish when the primary device language is Spanish**, and **English for every other language**. Regional Spanish variants are supported; a secondary Spanish language does not override an unsupported primary language. An explicit Android 13+ per-app locale override follows the same rule. Project documentation is English. The embedded VDO.Ninja website manages its own language independently. This is an experimental project: test the phones, accessories and network you will use for an event.

Download test builds from [GitHub Releases](https://github.com/efrencd/VillaCam/releases). Preview APKs use development signing; a future release signing key may require reinstalling the app, which clears its local settings.

## Features

- Landscape 16:9 video: **720p or 1080p at 30 fps**, H.264.
- SRT audio: **AAC stereo, 48 kHz, 128 kb/s**.
- Automatic reconnection and adaptive video bitrate.
- SRT latency: **60, 120, 200, 400 or 800 ms**.
- Live selection of automatic, built-in or USB microphone input, with actual-route reporting.
- Camera switching and mute controls over the local preview.
- Fixed start/stop and connection status with a blinking green live indicator.
- Embedded VDO.Ninja mode with a private camera ID and an OBS receiving link.

Preview controls are not transmitted. No local video files are recorded. Leaving the publishing screen or turning off the screen stops capture and recovery. Background publishing is not supported.

## Requirements

Android **6.0 / API 23 or later**, compatible camera/audio codecs, and OBS Studio on the receiving computer. Hosted VDO.Ninja mode also requires internet and a compatible, up-to-date Android System WebView/Chrome installation.

## Receive SRT in OBS

1. Connect phone and computer to a network allowing communication between devices. Guest Wi-Fi/client isolation may prevent this.
2. In OBS, add a **Media Source** and disable **Local File**.
3. Use this **Input**:

   ```text
   srt://0.0.0.0:9000?mode=listener&latency=200000&timeout=5000000
   ```

4. Set **Input Format** to `mpegts` and keep the source active.
5. Allow inbound **UDP 9000** through the receiving computer's firewall for the intended network.
6. In VillaCam, enter the computer's LAN address, for example `192.168.1.100`, port `9000`, and **200 ms** latency to match the example.
7. Tap **START** (**EMITIR** in Spanish). **STOP** (**DETENER**) also cancels reconnect attempts.

The phone is the SRT **caller**; OBS is the **listener**. Use a separate port and OBS Media Source for each phone. The current SRT stream ID is fixed to `camara`.

VillaCam uses **milliseconds**, while OBS URL latency uses **microseconds**: 60 ms = 60000; 200 ms = 200000; 800 ms = 800000. This buffer is not a measurement or guarantee of camera-to-screen delay. Start with 720p and 120–200 ms on a stable LAN; mobile/VPN connections may need 400–800 ms at both ends. Test lower OBS network buffering if minimizing delay is important.

A LAN address is not reachable directly from another network. Remote SRT needs a suitable VPN, public receiver/port forwarding or relay, configured separately. The app currently has **no SRT passphrase/authentication controls**; do not assume its SRT traffic is encrypted.

See the [official OBS SRT guide](https://obsproject.com/kb/srt-protocol-streaming-guide).

## Receive VDO.Ninja in OBS

1. Choose **VDO.Ninja · WebRTC** under **Streaming mode**. SRT is stopped first.
2. Keep the randomly generated camera ID or enter 8–64 letters, digits, underscores or hyphens. Use a different private ID per camera.
3. Optionally enter a password; the app does not persist it.
4. Tap **Copy OBS link** and transfer the full link to the computer.
5. In OBS, add a **Browser Source**, not a Media Source, and paste the URL. Set its canvas size, for example 1920 × 1080.
6. Tap **OPEN PUBLISHER** in the app, then use VDO.Ninja's controls to share the camera and start streaming.

**CLOSE PUBLISHER** closes the web publisher. **BACK TO SRT** returns to the native mode. In Spanish these buttons are **CERRAR EMISOR** and **VOLVER A SRT**. SRT is the default whenever the main screen starts; VDO.Ninja is not saved as the startup mode.

The official `https://vdo.ninja` client and Android WebView manage this mode's capture devices, quality, connectivity and recovery. Native SRT settings do not control it. Both modes cannot publish simultaneously. A loaded page does not confirm that OBS is receiving media. Leaving the app closes the publisher; reopen it manually after returning.

This is an independent integration, not an official VDO.Ninja app. Hosted-service access is subject to [VDO.Ninja's terms](https://docs.vdo.ninja/help/privacy-and-security-details/vdo.ninja-terms-of-service), which currently specify users aged **16 or older**. Signaling/STUN and relays may be involved. Keep stream IDs, passwords and full receiving links private. VillaCam's source license does not grant rights to third-party services.

## Microphone selection

The SRT input selector remains available while publishing:

| Option | Behavior |
| --- | --- |
| Automatic (Android) | Android chooses the input. |
| Built-in microphone | Requests a built-in microphone. |
| USB microphone | Requests the first enumerated USB audio input. |

The label reports the actual route when Android provides it, not just the requested device. Before capture starts, only the expected route can be shown. Android may reject or delay switching; a brief audio interruption is possible without restarting video/SRT.

Unplugging a selected USB input requests the built-in microphone, or automatic routing if unavailable, and displays a notice. USB is requested again when reconnected. Mute remains in effect. A two-transmitter receiver determines whether microphones are mixed or sent to separate stereo channels. Bluetooth routing is not configured.

This feature uses a guarded bridge into the pinned RootEncoder dependency. See [development notes](docs/DEVELOPMENT.md) before upgrading it.

## Recovery and adaptive bitrate

- One retry is queued **1 second after each detected failure**, without a maximum attempt count. Duplicate errors do not queue concurrent retries.
- Attempts recreate the SRT transport/codecs and request a keyframe after connection. Preview may disappear during recovery.
- A supervisor allows up to **15 seconds connecting** and **12 seconds without inbound packets** during an established stream when Android supports the app's receive counters. Inbound traffic does not prove OBS is displaying video. A one-second retry wait is not a one-second end-to-end recovery guarantee.
- Video targets range from **0.7–2.5 Mb/s at 720p** and **1.5–5 Mb/s at 1080p**, preserving resolution and frame rate.
- Every 2 seconds, adaptation checks sender congestion, frame drops, repeated SRT loss reports and RTT inflation. It reduces the target by 25% under pressure, subject to a 4-second decrease cooldown.
- After 4 clean seconds with inbound activity, it adds 25% of the profile maximum at most every 2 seconds. Policy tests recover from minimum to maximum in 8 simulated clean seconds; real recovery depends on feedback and the encoder.
- Reconnection retains a reduced target. A new session after stopping starts at the profile maximum.

These are heuristics, not a bandwidth guarantee. Displayed Mb/s are outgoing traffic reported by the library, not a measurement of available network capacity. Audio, encapsulation and retransmissions add overhead. Some device encoders can ignore or internally reject live bitrate changes.

## Build and install

Use **JDK 17**, **Android SDK Platform 35**, compatible build tools, and the included Gradle wrapper, or open the project in Android Studio. Set `ANDROID_HOME` or create an untracked `local.properties` with your own SDK location.

Dependencies use Google Maven, Maven Central and JitPack. The wrapper uses Gradle 8.11.1. Android Gradle Plugin 8.6.1 has an explicit D8/R8 8.13.19 override for newer Kotlin metadata in runtime dependencies.

Windows PowerShell:

```powershell
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

macOS/Linux:

```sh
sh ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The APK is at `app/build/outputs/apk/debug/app-debug.apk`. Debug keys usually differ between developers, so your build may not install over an APK signed elsewhere. The application ID is `es.colegio.camara`.

For public binary releases, preserve a dedicated release signing key for updates and keep it outside Git. Publish APKs as GitHub release assets rather than source files. No release signing key or automated release pipeline is included.

## Tests and limitations

With JDK 17, run the deterministic Java tests:

```powershell
.\tools\test-policies.ps1
```

Android checks: `gradlew.bat assembleDebug lintDebug` on Windows or `sh ./gradlew assembleDebug lintDebug` on macOS/Linux. The current lint analyzer emits Kotlin dependency metadata diagnostics; task success does not constitute complete analysis of those libraries.

Localization was checked in an API 36 emulator: English, Spanish (Spain/Mexico), and French with Spanish secondary (English fallback). Both native publishing screens were verified. The 79 localized keys have matching format arguments. Device-provided names and the embedded website are not translated by the native resources.

SRT has been exercised on an Android phone during development. VDO.Ninja was checked in an API 36 emulator for page loading, virtual-camera access and return to SRT without camera conflicts. USB routing, WebRTC reception in OBS and accessory/network combinations need further real-device verification.

Before an event, check audio and picture in OBS, network/receiver recovery, cancellation while offline, microphone switching and unplug/replug behavior, plus sustained multi-camera operation, temperature, battery and synchronization.

Not implemented: background publishing, simultaneous SRT/WebRTC, NDI, iOS, director chat/intercom, local recording or an integrated internet relay.

## Credits and license

**Author: Efren C.D.**

See [CONTRIBUTING.md](CONTRIBUTING.md), [development notes](docs/DEVELOPMENT.md) and [third-party notices](THIRD_PARTY_NOTICES.md). The ImageGen-created icon, original prompt and resource-generation details are in [assets/branding](assets/branding/README.md).

Original VillaCam project contributions are licensed under **Apache-2.0**. See [LICENSE](LICENSE) and [NOTICE](NOTICE). Third-party components retain their own licenses and notices.

