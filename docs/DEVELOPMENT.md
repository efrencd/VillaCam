# Development notes

Author and maintainer: **Efren C.D.**

## Layout

| Component | Responsibility |
| --- | --- |
| MainActivity | Native SRT controls, capture lifecycle and reconnect scheduling. |
| RecoveryMonitor | Pure Java monotonic-time timeout/retry policy. |
| AdaptiveBitrate | Pure Java bounded adaptation using SRT/network feedback. |
| AudioInputPolicy | Route preference with USB-to-internal fallback. |
| AudioInputRouter | Android input enumeration, live routing and actual-device reporting. |
| VdoActivity | Foreground-only official VDO.Ninja client in a WebView. |
| VdoLinks | Stream URLs and exact HTTPS origin validation. |
| LocalizedActivity / UiLanguage | Spanish for a primary Spanish locale, English otherwise. |

Policy tests in `tests/es/colegio/camara` run without Android. They cover retry deduplication/cancellation, timeouts, bitrate recovery/congestion bounds, USB fallback/replug selection and URL validation. They do not exercise hardware, codecs, Android routing decisions, NAT traversal or OBS reception.

## Localization

Both activities wrap their configuration using the primary system language, or an explicit Android 13+ per-app override: `es` selects Spanish, everything else selects English. Do not read only the incoming Activity locale order, which Android may already have reordered to prefer a supported secondary language. A normal locale configuration change recreates the activity, releasing capture using the existing foreground lifecycle; it does not silently resume publishing. English resources are complete defaults and Spanish resources have matching keys and formatting arguments. UI language selection has a pure Java test; both screens were checked in an API 36 emulator with English, Spanish variants and French with secondary Spanish. VDO.Ninja web content retains its own localization.

## Audio bridge

RootEncoder 2.8.1 does not expose AudioRecord for this camera API. AudioInputRouter reads `Camera2Base.microphoneManager` and `MicrophoneManager.audioRecord` using a guarded reflection bridge, then public Android `setPreferredDevice` and `getRoutedDevice` APIs. It does not replace the recorder or use Android hidden APIs.

Review field names, obfuscation/keep rules and lifecycle behavior before upgrading the dependency or enabling shrinking. Preferred and actual routes can differ; do not present a device as confirmed before Android reports it.

## Web publisher

VdoActivity restricts top-level navigation and camera/microphone grants to HTTPS `vdo.ninja`, with native permissions already granted. Unknown permission resources are not granted. There is no JavaScript-to-native object interface. File/content access and mixed content are disabled; TLS certificate errors are not bypassed.

The WebView is destroyed on pause and when returning to SRT. Do not use a loaded page as proof of an active stream. Web publishing uses third-party infrastructure and terms independently of VillaCam's source license.

## Build and release

Use the Gradle wrapper, JDK 17 and SDK 35. D8/R8 is overridden because runtime dependencies contain metadata newer than the bundled compiler supports. The older lint analyzer can still emit Kotlin metadata diagnostics; inspect reports rather than treating task success as full dependency analysis.

Keep application ID `es.colegio.camara` for updates. Keep signing material outside Git. Debug keys differ across machines. Locally delivered APKs are test builds, not a long-term release signing arrangement.

The public repository is `efrencd/VillaCam`, licensed under Apache-2.0. Keep only source/assets/wrapper files in Git. Local APK deliveries, logs, SDK paths, build output and signing files are ignored. Publish test APKs as clearly labeled preview release assets, with accompanying license notices. Never publish signing keys.
