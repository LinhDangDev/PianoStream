E2E hostile-input fixtures — phase 5a.

Device prep: push BOTH folders into the app's SAF-granted library root:
  adb push "E2E-VALID Dir" /sdcard/<granted-root>/
  adb push "E2E-HOSTILE Dir" /sdcard/<granted-root>/

E2E-VALID-* : well-formed cues with hostile NAMES (quotes, emoji, ~240-char).
  They reference fixture.flac and MUST land rows — proves hostile names are
  treated as data.
E2E-HOSTILE-*: intentionally unparseable cues (garbage bytes, >500 tracks,
  >4096-byte line, non-numeric TRACK). They MUST NOT land cue_sheets_raw rows
  and MUST NOT crash the scan.
