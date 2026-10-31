# Contributing to VillaCam

VillaCam is maintained by **Efren C.D.** Contributions and documentation improvements are welcome.

Read the [README](README.md) before changing capture, routing or transport code. Keep documentation in English. Put native UI text in `res/values/strings.xml` (English fallback) and `res/values-es/strings.xml` (Spanish), including errors, accessibility descriptions and dynamic status templates. Keep format arguments consistent. The primary device language selects Spanish only for `es`; other languages use English.

## Development

1. Fork and clone the repository.
2. Install JDK 17 and Android SDK 35, and configure your own SDK path.
3. Build using the Gradle wrapper.
4. Run `tools/test-policies.ps1` and Android checks appropriate to your changes.
5. Open a pull request describing the problem, resulting behavior and verification.

Keep changes focused. Document behavior changes and device-specific limitations. Do not claim real-device testing based solely on policy tests or an emulator.

Capture/audio changes need physical-device checks. Preserve foreground-only shutdown, explicit stop/cancel behavior, SRT as the startup mode and camera/microphone release when switching modes.

## Reporting issues

Include app/Android/OBS versions, phone model, publishing mode, reproduction steps and expected/actual behavior. For USB issues, include receiver/adapter models. Explain network conditions without posting private network details.

Remove passwords, private VDO.Ninja links, tokens, identifying information and student images/voices from reports, screenshots and logs. Reproduce with a test scene whenever possible.

## Dependencies and artwork

RootEncoder is pinned to 2.8.1. AudioInputRouter depends on that version's field layout; review the bridge when upgrading or enabling obfuscation. Preserve third-party licenses and notices.

The launcher artwork and ImageGen prompt are in `assets/branding`. To regenerate Android resource sizes on Windows, install ImageMagick and run `tools/build-launcher-icons.ps1`.

Do not commit SDK paths, keystores, credentials, build directories, logs or APKs. Binary releases belong in GitHub Releases and require the maintainer's release signing process.

Contributions intentionally submitted to this project are provided under Apache-2.0 unless explicitly stated otherwise. Only contribute code/assets you have permission to submit.
