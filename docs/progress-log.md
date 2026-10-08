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
- 2026-10-07 | 1a | Freeze commit `1efaac6` on main — contracts sealed; no
  Gradle/freeze-path edits allowed below this point.
- 2026-10-07 | 1b | `:core:youtube` extractor-core implemented (InnerTube
  WEB_REMIX client, client catalog + health + fallback, stream resolver with
  LRU cache keyed (videoId,clientId,itag) + 300s floor + F17 expire clamp,
  QuickJS cipher solver behind a `JsEngine` seam (F14 bounds unit-testable),
  remote-config registry with host allow-list + ETag + single-flight behind
  `remoteConfigEnabled`, :potoken-process WebView host with lockdown settings
  + write-only bridge + nav allow-list + separate VISITOR/GVS token caches,
  Hilt `YouTubeModule` + `YouTubeLocalWipe` hook).
- 2026-10-07 | 1b | GATE GREEN — `:core:youtube:test` 70 tests / 12 classes /
  0 failures (MockWebServer: fixture search/browse/suggestions, resolver
  itag 251→140 + cache hit + force-resolve + 429 + mismatch demotion + GVS
  binding separation; registry reject corpus; QuickJS deadline/memory/
  non-blocking via fake engine; expire clamp edges; WebView lockdown via
  Robolectric). `assembleGithubStubDebug` stays green. Not covered on JVM
  (deferred to 5a by design): native QuickJS engine path, real poToken mint,
  manual on-device smoke — noted in phase notes.
- 2026-10-07 | 1b | Fixes found by the suite: JSON fixture brace; ItemParser
  received inner renderer without wrapper key (carousel shelf dropped) →
  `rendererKey` parameter; suggestions walked `v["searchSuggestionRenderer"]`
  instead of matching the key; remote-config coalesce blocked a *fresh*
  forced refresh → only waiters satisfied in-flight are coalesced.
- 2026-10-07 | env | Robolectric runtime fetch bypassed Gradle repos and hit
  rate-limited repo1 (HTTP 429): seeded
  `org.robolectric:android-all-instrumented:14-…-i7` into `~/.m2` and pointed
  `robolectric.properties` at the GCS Maven mirror.
- 2026-10-07 phase-2: `:core:media:test :core:data:test` green (69 unit tests incl. ReplayGain/gain-calc, CC2 cache-key, resolve+403, queue assemble, L1 crossfade matrix, local-clip bounds, queue-state writer; 18 data tests incl. playback.db v1 schema via Robolectric — migration helper runs JVM-side per frozen dep set) + `:core:media:connectedDebugAndroidTest` 5/5 on emulator (exported FGS type, session intent-filter, CC9 allow-list, M6 report). Deviation: `PlaybackDatabaseMigrationTest` lives in `src/test/` (Robolectric) not `src/androidTest/` — core:data build.gradle (frozen) declares zero androidTest deps.
- 2026-10-07 phase-3: `:core:local:test :core:data:test` green (32 local tests: cue time bounds, golden cue fixtures incl. INDEX-00 pregaps/negative gains/CP1252/CRLF+BOM/top-level SONGWRITER, >4KiB line + >500 tracks + zero-INDEX-01 rejection, H7 disc-aware Σ(windows)+Σ(pregaps)==duration gate, depth-1/2 SAF scan, oversize/FILE-escape/STREAMINFO-less read guards, SecurityException abort, CC2 key byte-identity; 25 data tests incl. library.db v1 schema/FTS4/adversarial-MATCH + B1 reconcile). Deviations: `LibraryDatabaseMigrationTest`/`LibrarySearchDaoTest` in `src/test/` Robolectric (frozen deps, same as phase-2); `core:local` cannot see Room and `core:data` cannot see `core:local` → `LibraryDataAccess` seam in core:data + `LibraryScanDriver` interface (core:data) implemented by `LibraryScanDriverImpl` (core:local); `artworkOf` wired via `LocalLibraryIndex` (contentKey→disc artwork/flacDuration); `preloadPolicy.onUsedByPlayer` wired to `onMediaItemTransition`.
- 2026-10-07 phase-4: `test` green 257/257 across all 10 modules + `assembleGithubRealDebug` + `assembleFossRealDebug` + `compileGithubRealDebugKotlin` green (all 4 flavor variants assemble). VM tests: Search 6 (300ms debounce counting-fake, verbatim raw input, F12 zero-YouTube under localOnly/LOCAL scope, gated suggestions, disclosure gate), Library 6 (no-root empty, monotonic progress, 100d/1669t, M2 shrink-confirm, B1 missing banner), Player 9 (1Hz ticker sync, ±10s, next/prev cross-item, M9 inert queue ops incl. drag 3→1 = moveMediaItem(2,0), H5 typed extraction error, M6 retry→prepare), Settings 7 (every frozen key persists, remoteConfig confirm+host list, clearStreamCache/revoke reach impls, DELETE double-confirm full wipe order incl. DataStore fallback), Home 2 (recent+composers+yt shelves, F12 zero-browse). Compose BOM 2026.09.00, media3-ui-compose MiniController, Navigation3 back stack hoisted, app-scoped MediaController via PlaybackController, Coil3 125MB+125MB crossfade no FileUriMapper, SAF picker via StartActivityForResult+SafPermissions (never OpenDocumentTree), foss UpdaterOptIn stub vs github real seam.
  Deviations: (a) interface bindings for PlaybackRepository/LibraryRepository/PlaybackController moved from core `@Binds` modules into `app/src/{stub,real}/di/*Bindings.kt` — the freeze seam is per-flavor so the only clean injection point is the app module's flavor source sets (header comments document this; core modules now expose only Impl providers); (b) `StreamCacheEvictor` introduced as `fun interface` in PlaybackRepositoryImpl — Kotlin `suspend ()->Unit` provider/consumer wildcard mismatch under Dagger; (c) `YouTubeLocalWipe` promoted internal→public (phase-1b visibility defect, app could not inject it); (d) `@Named("secondaryGain")` qualifier on secondary ReplayGainAudioProcessor + consumer to resolve DuplicateBindings; (e) prod fix: SearchViewModel.runQuery reads localOnlySearch/sendSearchSuggestions via `.first()` inline (collector-flag race could leak a query on F12); (f) prod fix: LibraryViewModel scanning flag now derives from progress `currentFolder!=null` — a trailing progress emission could otherwise re-arm `scanning=true` after result; (g) prod fix: SettingsViewModel.confirmDeleteAll no-ops unless deleteAll dialog open (M2 — a stray confirm would wipe); (h) prod fix: PlayerViewModel onPlayerError maps `error.cause as? ExtractionException` → typed message + `retryCurrent()`→`player.prepare()` wired to PlaybackErrorState Retry; (i) manifest `android:name` fully qualified (`com.pianostream.app.PianoStreamApp`) — Robolectric + tooling resolve `.`-shorthand against the namespace; (j) applicationId stays `com.pianostream` (namespace), app classes live under `com.pianostream.app.*`.
  (k) `LibraryScanDriverImpl`: `deleteDiscsNotIn` skipped on shrinking scans (M2 — stale rows kept until LibraryViewModel.confirmShrink wipes+rescans; warning text updated).
- 2026-10-07 phase-5a (emulator leg): `githubRealDebug` + androidTest installed on emu64xa; real bindings green end-to-end. E2E suite authored — ColdStartScan / YouTubeResolvePlay / QueueReorderResume / HostileInput / Phase5bGate + fixtures. Emulator-green: HostileInput 4/4, Phase5bGate 2/2, QueueReorder, foreign-controller + hostile-URI rejection. Env-blocked (verified, not app bugs): coldScan ground-truth (needs 100-disc corpus on device), streamUri (datacenter IP → YouTube `LOGIN_REQUIRED`, confirmed via direct InnerTube replay), battery/storage lab gates. 7 real bugs found+fixed: Route @Serializable, SAF WRITE-flag crash, tap-to-play wiring, grantError banner hidden when hasRoot (E2), removeFromQueue not mirrored to queue_items (B9/M9), revokeLibraryAccess crash on external revoke, ScanSummary/warnings never rendered (E2). cue_sheets_raw corrected expectation: failed cues may record hash rows — contract is zero tracks. Docs: docs/e2e/qa-matrix.md, docs/blocker.md — gate needs dev device + corpus + residential IP.
- 2026-10-07 aurora-ui (user picked direction A): ported Melody's design language — Aurora.kt tokens (black canvas, Glass/GlassStrong/Hairline, AccentPresets, luminance-based onAccent); Aurora-mapped dark/light schemes; PillNavBar replaces M3 NavigationBar (sibling-bar pattern, hides while player expanded); ExpandingPlayer island in feature/player replaces MiniController + NowPlaying route (52dp frosted dock, offset-Animatable morph: artwork mini→large, swipe/BackHandler collapse, queue via ModalBottomSheet); DiscCard/TrackRow/Misc restyled (glass cards, accent current-track, note placeholders); TabRow → Aurora colors; theme prefs finally wired (themeMode + dynamicColor were dead toggles — Dynamic OFF = full Aurora, ON = Material You); settings gear added on Home (Route.Settings was unreachable). Zero Gradle/freeze edits. Both variants build; androidTest compiles; emulator-verified: Home/Library/dock/expanded/queue sheet all Aurora; QueueReorderResume 1/1 + HostileInput 4/4 green on the new flow (dock tap added to the E2E path).
- 2026-10-07 player-redesign (user refs): dock = circle thumb + accent title + small prev/next + white round play (Spotify-Connect-mini style); expanded = vinyl-circle artwork + white ThinTrackBar overlaid on its bottom chord (ported from melody's Cue slider) + frosted control card + shuffle/queue/repeat row; queue rows = circular thumbs + accent current. Emulator-verified on real FLAC playback.
- 2026-10-08 player-vinyl: expanded circular artwork now rotates linearly once per 12 seconds while playback is active and freezes at its current angle when paused; rotation is isolated to the inner artwork layer so the morphing mini→large geometry stays anchored. Build + androidTest compilation green.
- 2026-10-08 player-turntable: expanded player re-skinned after the Neumorphism Vinyl Player reference (dribbble.com/shots/12239558) — charcoal neumorphic platter, grooved record spinning at 33⅓ rpm with artwork as its label, gold tonearm that swings onto the record while playing, gold inset slider, neumorphic transport; dock thumb is a mini spinning record. Same a11y labels. githubReal+fossReal build + androidTest compile green; verified on emulator (no physical device).
- 2026-10-08 app-wide turntable skin: Aurora tokens retuned to charcoal (#1B1C1F) + champagne gold accent; AuroraDark scheme remapped; DiscCard draws a vinyl record (shared drawVinylRecord/VinylDisc in designsystem); TrackRow placeholder -> mini record; Search field inset gold-focus; chips/switch/slider/buttons recolored gold; queue sheet charcoal. Builds githubReal/fossReal + androidTest compile green.
- 2026-10-08 artwork-fix: two pipeline breaks — (1) `LibraryScanDriverImpl` skip-write fingerprint covered only flac size+mtime, so a `Front.jpeg` copied beside the cue/flac after the first scan never re-wrote the disc row (`artworkUri` stayed null → "0 new"); now `unchangedSince` also compares resolved `artworkUri` (add/remove/replace-by-new-doc all trigger rewrite). (2) `LocalMediaItemFactory` stamped only `artworkData` (lockscreen bytes) — `mediaMetadata.artworkUri` stayed null so in-process readers (turntable `VinylLabel`, dock `MiniVinyl`, queue `TrackRow`) showed the red fallback; now stamps `setArtworkUri` alongside. Tests: `LibraryScanDriverImplTest` (5 fingerprint cases) + 2 `LocalMediaItemFactoryTest` artworkUri cases. `:core:local:test :core:data:test :core:media:test` green (MergedManifestTest needs `processDebugManifest` first — pre-existing).
- 2026-10-08 add-to-queue: search rows get a playlist-add icon beside the heart — `AppViewModel.enqueue` appends assembled items to the live player via addMediaItems and rewrites the durable queue as existing++new (cursor passed through so queue_state survives; skip player append when disconnected — M9 restore owns the cold timeline). Toast "Added to queue". `AppViewModelTest` 4 cases green (append+persist, disconnected persist-only, dead local row, no-op).
- 2026-10-08 playlists (devin/playlists): `playback.db` v1→v2 — new `playlists` + `playlist_items` (composite PK playlistId+mediaId, FK CASCADE) with real `Migration(1,2)`, `PlaybackModule.addMigrations`; repo gains observePlaylists/create/rename/delete/observePlaylistTracks/addToPlaylist/removeFromPlaylist (default no-ops on the interface so the frozen `app/` stub fake still compiles — stub surfaces no playlists). Library "Playlists" tab now lists user playlists (replaces the placeholder queue view — queue still lives in player QueueSheet): "+ New playlist" dialog, detail view with back/⋯ Rename/Delete, ordered track rows play via onPlayQueue, remove-track affordance; add-to-playlist trailing icon on Tracks + Favorites rows opens a ModalBottomSheet (playlists + create-then-add). Tests: +5 in PlaybackDatabaseMigrationTest (v2 tables, CRUD/counts, ordering/re-add-to-end, FK cascade, real file migration preserving favorites+history) + 3 in LibraryViewModelTest. core:data + feature:library + app githubReal/stub compile green; 46 unit tests green; emulator-verified end-to-end (SAF grant → scanned mp3 → sheet → "New playlist" → detail → rename).
>>>>>>> origin/devin/playlists
