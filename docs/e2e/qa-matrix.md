# QA Matrix — Phase 5a device E2E

Filled during Phase 5a. `emu64xa` = Android emulator (API 34, x86_64, Google
datacenter IP). `device` = the dev phone required by the 5a gate (API 27+,
the 21 GB "The Piano" corpus copied to local storage, residential/mobile IP).

Legend: **PASS** verified green · **ENV** authored + running, blocked by
environment (not an app failure) · **5b** authored, control lands in Phase 5b
· **MAN** manual lab procedure, not JUnit.

## E2E classes

| Case | Class / test | emu64xa | dev device |
|---|---|---|---|
| Cold start → SAF pick → scan → 100 discs / 1669 tracks / 205 JPEGs in <30 s | ColdStartScanE2eTest.coldScan | ENV — emulator tree holds only fixtures (3 discs); ground truth unreachable without the corpus | required |
| Cue-disc track boundaries ±100 ms, gapless, ReplayGain attenuation | manual + QueueReorder flows | ENV — needs the real FLAC corpus | required |
| CD38→CD39 inter-disc gap <150 ms (FM-L1) | manual | ENV — needs corpus | required |
| Search → queue → itag-251 stream plays | YouTubeResolvePlayE2eTest.streamUri | ENV — datacenter IP → YouTube `LOGIN_REQUIRED` ("not a bot") for every unauthenticated client (verified via direct InnerTube `player` call from the VM — `status: LOGIN_REQUIRED`, 0 formats). Resolve code path executes correctly up to the API answer. | required — residential/mobile IP |
| Kill mid-play → relaunch restores queue/index/position ±1 s (FM-M9) | QueueReorderResumeE2eTest.reconnect | PASS (durable `queue_items` restore verified: `getMediaItemAt(0).mediaId` matches persisted head after full controller reconnect) | re-run on device |
| Queue reorder/remove persists across relaunch (B9/M9) | QueueReorderResumeE2eTest.remove | PASS — `queue_items` mirrors UI ops; head advances correctly | re-run on device |
| malformed.cue → reject+log, zero bogus rows | HostileInputE2eTest.a_ | PASS — parser warning surfaces ("line > 4096 bytes"); disc stub has `scanComplete=1`, **0 tracks** | re-run on device |
| oversize.cue → SIZE guard skip, reported | HostileInputE2eTest.a_ (fixture in tree) | PASS — guarded at read, warning reported | re-run on device |
| hostile names treated as data (quotes/emoji/long) | HostileInputE2eTest.a_ | PASS — `E2E-VALID hostile ' quotes -- ; DROP TABLE discs` disc lands intact | re-run on device |
| hostile FTS batch (", *, OR, NEAR(, 500-char, NUL) | HostileInputE2eTest.b_hostileFts | PASS — no crash, no hang, UI responsive | re-run on device |
| foreign controller → zero commands / rejected bind (CC9) | YouTubeResolvePlayE2eTest.foreign | PASS — `MSessionService: Ignoring connection from invalid package name` (rejected bind) | re-run on device |
| `file://` + `https://` media items rejected (CC9/F4) | YouTubeResolvePlayE2eTest.hostileFileUri + networkUrlScheme | PASS — both leave `mediaItemCount==0` | re-run on device |
| tampered `player_configs.json` → refused, fail-closed (F2/U3) | HostileInputE2eTest.b_tamperedPlayerConfig | PASS — app healthy; on bot-blocked IP the stronger signal (no stream from tampered data) needs the device | re-run on device |
| grant lost mid-scan → banner / refuse-wipe surface (M2) | HostileInputE2eTest.zz_grantLoss | PASS — banner "Library access was lost" surfaces with root set (E2 gap found+fixed: banner was hidden when `hasRoot`) | re-run on device |
| Revoke access → releases grant + clears `libraryRootTreeUri` (F8) | HostileInputE2eTest.zz_grantLoss | PASS — key removed; revoke hardening fix: no crash when grant already gone | re-run on device |
| user-CA → all egress fails (F7) | Phase5bGateE2eTest.userCa | PARTIAL — non-system-CA chain rejected from app process (`self-signed.badssl.com` → SSLHandshakeException). Full MITM rig is MAN (below). | required |
| tampered APK → verify terminal, delete, no install (F1/F6) | Phase5bGateE2eTest.tamperedApk | 5b — authored, assume-skips until `ApkVerifier` lands | 5b |
| 30-min battery playback, crossfade on/off → crossfadeMs default | manual | ENV — needs device + corpus | required |
| cacheBudget shrink→LRU; `rm -rf media3` mid-play → cache-reset recovery (FM-M4) | manual | ENV — needs device | required |
| foss real flavor — full matrix | — | ENV (github real verified) | required |

## Manual rig notes

- **MITM rig (F7 full proof):** install a test CA into the user store
  (Settings → Security → Encryption & credentials → Install a certificate),
  route device traffic through mitmproxy presenting a cert signed by that CA;
  every catalog/registry/stream request must fail TLS. Expected with the
  shipped `network_security_config` (system anchors only): all fail.
- **Emulator SAF grant** survives `pm clear`/revoke only as long as the tree
  still exists; `HostileInputE2eTest.zz_*` intentionally destroys it — always
  re-grant before re-running the suite.
- **Fixture push:** `E2E-VALID Dir`/`E2E-HOSTILE Dir` live in
  `app/src/androidTest/assets/e2e/`; push inside the granted tree
  (`/sdcard/Music/TestDisc/` on the emulator) — the grant is read-only, the
  suite cannot self-seed.

## Bugs found by this suite (all fixed in app code — zero freeze edits)

1. `Route` missing `@Serializable` → nav state-save crash.
2. `SafPermissions` threw when the picker offered a WRITE flag → grant crash.
3. Track tap navigated without playing → wired `onOpenTrack` → PlaybackController.
4. `grantError` banner unreachable while `hasRoot` (E2) — hoisted render.
5. `removeFromQueue` didn't mirror into `queue_items` (B9/M9) — durable queue now mirrors.
6. `revokeLibraryAccess` crashed if the grant was already gone externally — runCatching.
7. Scan summary/warnings never rendered (E2) — added to Library screen.
