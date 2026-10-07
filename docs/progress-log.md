# PianoStream — progress log

One line per checkpoint: phase, gate result, blockers.

## Decisions logged

- **O1 (toolchain pair, scaffold-time pick):** Gradle **9.6.0** + AGP **9.4.1** +
  KSP **2.3.12**. Discovered empirically: Hilt 2.60.1 requires AGP ≥9.0.0;
  AGP 9.4.1 requires Gradle ≥9.6.0; AGP ≥9 ships built-in Kotlin support, so the
  `org.jetbrains.kotlin.android` plugin alias is *removed* from every module
  (`kotlin-compose` + `kotlin-serialization` plugin aliases kept). KSP has no
  Kotlin-2.4-prefixed release; 2.3.12 is the stable paired with Kotlin 2.4.
- **compileSdk 37 (plan-deviation, pre-freeze):** the plan pins compileSdk 36
  but its own pinned deps (Compose BOM 2026.09.00 → compose 1.12.x, Coil 3.6.3,
  activity-compose 1.13.0, lifecycle 2.11.0, core-ktx 1.19.1) declare
  `minCompileSdk=37` in AAR metadata — the AAR check is a hard error and
  `android.suppressUnsupportedCompileSdk` is no longer honoured on AGP 9.
  Resolved by compiling against **android-37.0** while keeping **minSdk 27 /
  targetSdk 36** untouched (compileSdk is build-time API surface only; it opts
  the app into no new runtime behaviour, and the deps' API-37 code paths are
  runtime-gated). Flagged here and to Linh; trivially revisable pre-freeze.
- **Repo content filter:** plan's loose `com.google.*` on google() was tightened
  to `androidx.*`, `com.android.*`, `com.google.android.*` — dagger/ksp/truth
  are Central-only (404-verified on dl.google.com) and must not resolve via
  google(). mavenCentral() excludes those three group regexes.
- **Keys generated (local, untracked):** `release.jks` (RSA-4096, alias
  `pianostream`) + `keystore.properties`; minisign pair in
  `~/.minisign-pianostream/`. BuildConfig pins baked:
  `RELEASE_CERT_SHA256=6bbcad05…c2a46c4`,
  `UPDATE_PUBKEY=RWQRXyApgEKBN4r538dcgQPDOwhBf1HiDH6sxn9D1DJH2CFQKMY14oDW`,
  `UPDATE_REPO=LinhDangDev/PianoStream`, `UPDATE_ASSET=PianoStream-release.apk`.

## Log

- 2026-10-07 | 1a | scaffold: repo at /home/ubuntu/repos/PianoStream; 10 modules
  (`:app`, `:core:{designsystem,data,local,media,youtube}`,
  `:feature:{home,search,library,player}`), flavors `dist={github,foss}` ×
  `source={stub,real}`; gradle.properties/settings/libs.versions.toml pinned.
- 2026-10-07 | 1a | gate `./gradlew :app:assembleGithubStubDebug` — **GREEN**;
  also green: githubRealDebug, fossStubDebug, fossRealDebug (4/4 variants).
- 2026-10-07 | 1a | gate freeze-set unit tests — **GREEN**:
  `PianoStreamUriTest` (round-trip + 25 hostile inputs + boundary-vs-URI
  distinction + traversal), `RedactTest`, `ResolvedStreamRedactionTest`,
  `BuildConfigContractTest` (all 5 pins readable + shape-checked).
- 2026-10-07 | 1a | `gradle/verification-metadata.xml`: quickjs-kt 1.0.15
  pinned by SHA-256 (root jar/module/pom + `-android` release aar/module/pom);
  `<trusted-artifacts>` trusts every non-dokar3 group (repo filter already
  constrains them). Smoke check: corrupting the aar checksum fails
  `:core:youtube:compileDebugKotlin` ("failed verification:
  quickjs-release.aar"); restoring it rebuilds green. Verified live.
- 2026-10-07 | 1a | Interface Freeze set written with KDoc citing CC1–CC11 /
  SEC-F* / F* / M* / H* / L1 at definition sites; freeze commit
  `freeze: shared contracts (plan 20261007-pianostream)` next.
