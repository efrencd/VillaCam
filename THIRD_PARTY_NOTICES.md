# Third-party notices

VillaCam is authored by Efren C.D. Its Apache-2.0 license applies to the original project contributions, not ownership of third-party components, names or hosted services.

## Runtime components

- **RootEncoder 2.8.1**, by Pedro Sánchez (pedroSG94), is distributed under Apache-2.0. VillaCam integrates its camera, encoder and SRT APIs. Other protocol modules arrive transitively; their presence does not mean VillaCam exposes every protocol. See [RootEncoder source](https://github.com/pedroSG94/RootEncoder/tree/2.8.1) and its [license](https://github.com/pedroSG94/RootEncoder/blob/2.8.1/LICENSE.txt).
- **AndroidX WebKit, core, lifecycle, collection, versionedparcelable and annotations**, **Kotlin**, **KotlinX**, **Ktor**, and **JetBrains annotations** use Apache-2.0 for the modules in the resolved inventory. See [AndroidX](https://android.googlesource.com/platform/frameworks/support/), [Kotlin](https://github.com/JetBrains/kotlin), [Ktor](https://github.com/ktorio/ktor), and [Java annotations](https://github.com/JetBrains/java-annotations/tree/23.0.0).
- **Bouncy Castle** components use the Bouncy Castle license (MIT-style). See [license text](third_party/licenses/BouncyCastle.txt) and [upstream licensing](https://www.bouncycastle.org/licence.html).
- **SLF4J API** uses the MIT license. See [license text](third_party/licenses/SLF4J.txt) and [upstream licensing](https://www.slf4j.org/license.html).

The complete Apache-2.0 text is included in [LICENSE](LICENSE). Preserve component-specific copyright and NOTICE information when redistributing dependencies. Release bundles should include this file, NOTICE, LICENSE and the additional license texts; this inventory does not replace notices embedded in upstream artifacts.

The table below records the resolved debug runtime modules for VillaCam 0.14.0. Versions come from Gradle's resolved configuration; declared licenses were checked using cached POM metadata and upstream sources. Build-tool dependencies are separate. Recheck this inventory when upgrading or creating a public binary release.

| Resolved module | License |
| --- | --- |
| androidx.annotation:annotation-jvm:1.10.0 | [Apache-2.0](LICENSE) |
| androidx.arch.core:core-common:2.0.0 | [Apache-2.0](LICENSE) |
| androidx.collection:collection:1.0.0 | [Apache-2.0](LICENSE) |
| androidx.core:core:1.1.0 | [Apache-2.0](LICENSE) |
| androidx.lifecycle:lifecycle-common:2.0.0 | [Apache-2.0](LICENSE) |
| androidx.lifecycle:lifecycle-runtime:2.0.0 | [Apache-2.0](LICENSE) |
| androidx.versionedparcelable:versionedparcelable:1.1.0 | [Apache-2.0](LICENSE) |
| androidx.webkit:webkit:1.11.0 | [Apache-2.0](LICENSE) |
| com.github.pedroSG94.RootEncoder:common:2.8.1 | [Apache-2.0](LICENSE) |
| com.github.pedroSG94.RootEncoder:encoder:2.8.1 | [Apache-2.0](LICENSE) |
| com.github.pedroSG94.RootEncoder:library:2.8.1 | [Apache-2.0](LICENSE) |
| com.github.pedroSG94.RootEncoder:rtmp:2.8.1 | [Apache-2.0](LICENSE) |
| com.github.pedroSG94.RootEncoder:rtsp:2.8.1 | [Apache-2.0](LICENSE) |
| com.github.pedroSG94.RootEncoder:srt:2.8.1 | [Apache-2.0](LICENSE) |
| com.github.pedroSG94.RootEncoder:udp:2.8.1 | [Apache-2.0](LICENSE) |
| com.github.pedroSG94.RootEncoder:whip:2.8.1 | [Apache-2.0](LICENSE) |
| io.ktor:ktor-http-jvm:3.5.2 | [Apache-2.0](LICENSE) |
| io.ktor:ktor-io-jvm:3.5.2 | [Apache-2.0](LICENSE) |
| io.ktor:ktor-network-jvm:3.5.2 | [Apache-2.0](LICENSE) |
| io.ktor:ktor-network-tls-jvm:3.5.2 | [Apache-2.0](LICENSE) |
| io.ktor:ktor-utils-jvm:3.5.2 | [Apache-2.0](LICENSE) |
| org.bouncycastle:bcpkix-jdk15to18:1.84 | [Bouncy Castle](third_party/licenses/BouncyCastle.txt) |
| org.bouncycastle:bcprov-jdk15to18:1.84 | [Bouncy Castle](third_party/licenses/BouncyCastle.txt) |
| org.bouncycastle:bctls-jdk15to18:1.84 | [Bouncy Castle](third_party/licenses/BouncyCastle.txt) |
| org.bouncycastle:bcutil-jdk15to18:1.84 | [Bouncy Castle](third_party/licenses/BouncyCastle.txt) |
| org.jetbrains.kotlin:kotlin-stdlib:2.3.21 | [Apache-2.0](LICENSE) |
| org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0 | [Apache-2.0](LICENSE) |
| org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0 | [Apache-2.0](LICENSE) |
| org.jetbrains.kotlinx:kotlinx-io-bytestring-jvm:0.9.1 | [Apache-2.0](LICENSE) |
| org.jetbrains.kotlinx:kotlinx-io-core-jvm:0.9.1 | [Apache-2.0](LICENSE) |
| org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.11.0 | [Apache-2.0](LICENSE) |
| org.jetbrains:annotations:23.0.0 | [Apache-2.0](LICENSE) |
| org.slf4j:slf4j-api:2.0.18 | [MIT](third_party/licenses/SLF4J.txt) |

## External services and tools

**VDO.Ninja** is loaded from its official HTTPS site inside a WebView. Its website/client code is not copied into this repository. Hosted signaling, STUN and relay access is governed by the service's own [terms](https://docs.vdo.ninja/help/privacy-and-security-details/vdo.ninja-terms-of-service) and [privacy information](https://docs.vdo.ninja/help/privacy-and-security-details), not VillaCam's license. The service currently specifies users aged 16 or older. This integration does not imply endorsement.

**OBS Studio** is a separate receiver application, not bundled with VillaCam. Its names and marks belong to their respective holders.

**Gradle wrapper 8.11.1** is included to build the project; Gradle is Apache-2.0 licensed with its own third-party notices. **Android Gradle Plugin** and **D8/R8** are build dependencies downloaded by Gradle rather than vendored source. **ImageMagick** is an optional external tool used only to package icon sizes; it is not bundled. Android SDK tools and Android System WebView remain external platform components.

## Artwork

The settings tab glyph follows the Material Design settings icon (Google, Apache-2.0). Other sidebar glyphs are simple geometric vector paths.

The VillaCam launcher artwork was generated with the built-in ImageGen tool. Original artwork and prompt provenance are retained in [assets/branding/README.md](assets/branding/README.md). No third-party school logo was used as an input reference. The repository does not claim that generated artwork is exclusive or that third-party trademarks are licensed by Apache-2.0.

