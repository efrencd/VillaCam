# VillaCam

<img src="assets/branding/villacam-icon-preview.png" alt="VillaCam icon" width="120" />

**An Android camera for school television and live production.**

Created by **Efren C.D.** for Villavision, the school television project at Villaverde.

VillaCam turns an Android phone into a wireless camera for OBS Studio. VillaCam restores the last selected streaming mode; Choose **SRT** or **WebRTC**. VDO.Ninja WHIP is a provider within WebRTC.

Current version: **0.32.0**. The native interface automatically uses **Spanish when the primary device language is Spanish**, and **English for every other language**. Regional Spanish variants are supported; a secondary Spanish language does not override an unsupported primary language. An explicit Android 13+ per-app locale override follows the same rule. Project documentation is English. This is an experimental project: test the phones, accessories and network you will use for an event.

Download test builds from [GitHub Releases](https://github.com/efrencd/VillaCam/releases). Preview APKs use development signing; a future release signing key may require reinstalling the app, which clears its local settings.

## Features

- Landscape 16:9 video: **720p or 1080p at 30 fps**, H.264.
- SRT audio: **AAC stereo, 48 kHz, 128 kb/s**.
- Automatic reconnection and adaptive video bitrate.
- Native phone stabilization: off, electronic (EIS) or optical (OIS), with camera metadata confirmation.
- SRT latency: **60, 120, 200, 400 or 800 ms**.
- Live selection of automatic, built-in or USB microphone input, with actual-route reporting.
- Camera switching and mute controls over the local preview.
- Circular start/stop control over the bottom-center preview; white shutter when idle, red stop square while publishing or reconnecting.
- Three icon tabs aligned to the preview top: options, web panel and about.
- Connection status over the upper-left preview, with a blinking green SRT live indicator.
- Vertical SRT microphone peak meter over the right edge of the preview (dBFS, colored bands and peak hold).
- Configurable receive-only web panel that stays loaded across tabs, with independent web audio muting where supported.
- Native publishing to VDO.Ninja with WHIP.

Preview controls are not transmitted. No local video files are recorded. Leaving the publishing screen or turning off the screen stops capture and recovery. Background publishing is not supported.

## Local WebRTC trial (Windows OBS)

1. Connect the Android phone and the OBS computer to the same LAN.
2. In VillaCam select **WebRTC**, choose **Local network**, enter a unique camera name such as `villacam-01`, then tap the circular start button. VillaCam remembers the name and prefers the phone’s Wi-Fi IPv4 address, ignoring VPN networks.
3. Add a **Browser Source** in OBS and paste the displayed URL, for example `http://villacam-1234abcd.local:8080/`. Use the displayed IP URL if Windows or your network cannot resolve mDNS.
4. Keep VillaCam on the camera screen. The trial supports one viewer per phone. If OBS blocks autoplay with sound, video starts muted. Open **Interact** on the source and click **Enable sound**.

The Android phone hosts the receiver page and signaling on the LAN. Audio and video travel directly over WebRTC, with no external service. The trial has no access key. Use it on a trusted network. VillaCam rebuilds the LAN listener and republishes mDNS when the network or address changes. Keep the OBS browser source loaded with the .local URL so it can reconnect. The new LAN must be reachable from the receiver, and the receiver must resolve the name to the new address. A numeric URL must be updated manually after an IP change. Native WebRTC uses the main preview and sidebar, supports the 720p and 1080p capture profiles, camera switching and muting, and allows requesting the default, built-in or USB microphone before or during streaming. Android decides the active route. WebRTC meters microphone capture and shows measured outgoing video bitrate once connected. Video bitrate is adaptive by default. The optional 2, 4, 6 or 8 Mb/s setting caps video bitrate while preserving congestion control. Native SRT retains its stabilization and microphone routing controls.

## WebRTC over the Internet

Under **WebRTC → Over the Internet**, select **VDO.Ninja (WHIP)** for native camera publishing through the documented VDO.Ninja ingest endpoint. Use a unique camera name. Start publishing and open the exact link shown in an OBS Browser Source, for example `https://vdo.ninja/?whip=villacam-example`. Keep the source open while connecting. The app discovers STUN and TURN through the service’s WHIP response. Public service availability and mobile network compatibility should be tested before production use.

Select **Custom signaling server** to use the VillaCam Worker described below.

Choose **WebRTC → Connection → Over the Internet**, enter the HTTPS origin of your signaling deployment and a camera name, then tap the start button. Copy the camera link shown by VillaCam into an OBS Browser Source. Keep that source open through network changes. The OBS link uses only the camera name, such as `/?view=villacam2`. Anyone with that name can watch. Choose a distinct name for each camera. Remote sessions support one OBS viewer.

The Worker and deployment steps are in [signaling/README.md](signaling/README.md). The free STUN service is used by default. TURN fallback requires optional server credentials. VillaCam reports direct or TURN based on the selected ICE route when statistics are available.

## Native stabilization (SRT)

In Options, choose **Off**, **Electronic (EIS)** or **Optical (OIS)**. Off is the default. Stop streaming to change the setting. The preference is saved and reapplied when reopening/reconnecting the camera or switching lenses. Only one stabilization mode is requested at a time. Unsupported selections explicitly report that the camera does not support the mode and request both modes off.

The status below the selector reports capture metadata, rather than treating an accepted request as proof of active stabilization. Support and results depend on the camera and video profile; EIS may crop the image or increase delay. Test 720p and 1080p on the actual phone. VDO.Ninja capture is independent of this native setting.

## Sidebar and director panel

The three icon tabs open **Options** (gear), **Web panel** (globe) and **About** (information). Long-press a tab for its label. The SRT shutter button stays over the bottom-center preview, outside the transmitted image. The right panel spans the preview height.

In Options, enter the full panel URL and tap **Save and open panel**. Both HTTPS and HTTP are accepted, including local LAN dashboards. HTTPS is normally required for receiving WebRTC audio; plain HTTP can be useful for a local text chat. Navigation stays on the configured origin. Reload and close controls live in Options and affect only this panel, not SRT capture. The loaded page uses the full web tab without a permanent toolbar or status banner. A URL is saved locally; do not put passwords or secrets into it.

The panel cannot access the camera or microphone. Web audio is **off by default** when Android System WebView supports independent muting; enable it in Options for director-to-phone audio. A tap inside the page may be required to start playback. Older WebViews without native audio-muting support show a disabled switch and a notice: use Android volume or update System WebView. Headphones are **optional**, and unplugging them never closes the page. **Android selects the playback output**; VillaCam does not force a separate headphone route or change the streaming microphone to route web audio. Select/check the output in the phone settings. With audio enabled, unplugging headphones may move playback to the speaker.

Tabs retain the live web session while the activity stays foregrounded. Leaving the screen closes the panel and capture; the configured URL remains saved.

The native microphone meter reads the existing SRT PCM16 capture without modifying samples or opening another recorder. It starts while SRT captures audio, shows mute explicitly and clears on stop/reconnect. The slim, transparent meter uses a peak **dBFS** scale without numeric labels; it is not a calibrated loudness measurement. WebRTC also meters native microphone capture. Preview overlays are never encoded into SRT.

## Requirements

Android **6.0 / API 23 or later**, compatible camera/audio codecs, and OBS Studio on the receiving computer. VDO.Ninja WHIP requires Internet access.

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

SRT has been exercised on an Android phone during development. USB routing, WebRTC reception in OBS and accessory/network combinations need further real-device verification.

Before an event, check audio and picture in OBS, network/receiver recovery, cancellation while offline, microphone switching and unplug/replug behavior, plus sustained multi-camera operation, temperature, battery and synchronization.

Not implemented: background publishing, simultaneous SRT/WebRTC, NDI, iOS, director chat/intercom, local recording or an integrated internet relay.

## Credits and license

**Author: Efren C.D.**

See [CONTRIBUTING.md](CONTRIBUTING.md), [development notes](docs/DEVELOPMENT.md) and [third-party notices](THIRD_PARTY_NOTICES.md). The ImageGen-created icon, original prompt and resource-generation details are in [assets/branding](assets/branding/README.md).

Original VillaCam project contributions are licensed under **Apache-2.0**. See [LICENSE](LICENSE) and [NOTICE](NOTICE). Third-party components retain their own licenses and notices.

