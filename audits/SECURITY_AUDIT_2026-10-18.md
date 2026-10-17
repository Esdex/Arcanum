# Arcanum Security Audit

**Date:** 18 October 2026
**Auditor:** Claude Opus 5.5
**Type:** Leak audit of everything built since v1.6 (fifth in series; follows 16, 19 and 20 June and 9 September 2026)
**Scope:** 21 commits, 37 Kotlin files and the manifest, ~1,900 inserted lines between `v1.6` and `58ff735` - opened as a step of preparing the 1.6.1 release

---

## Goal

The same question as in September, asked of a smaller diff: **can anything that belongs inside a vault get out of it?** The cipher and the native layer are out of the question this time for a plain reason - neither changed. What did change is the media side (a second player and media session, a notification that opens a screen), the file manager (search through subfolders, links resolved to their real paths, links indexed for the gallery), the gallery (a fast scroller, a viewer handed its list by the screen), and the date editor.

---

## Threat Model

As on 9 September. **Primary attacker:** someone who has the phone, or a copy of what is on it - app-private storage, a bug report, an `adb logcat` capture - looking for what the vaults contain and whether a hidden volume exists. **Secondary:** another app installed on the same phone, which can send intents to whatever Arcanum exports and connect to whatever it exposes.

**Out of scope:** the cryptography and the native layer (unchanged since v1.6), and anything that predates v1.6 unless it was touched.

---

## Method

Against the code at `58ff735`:

- `git diff v1.6..HEAD` for the file set. No file under `app/src/main/cpp` changed, so the release-library strings check from September still stands and was not repeated.
- Every line added to Kotlin since v1.6 searched for logging (`Log.*`, `println`, `printStackTrace`). **Measured: none.** The two temporary traces written during this cycle (for #194 and #183) were removed before their commits.
- Each new surface read for what it hands to the system: the two media sessions, the notification's activity intent, the exported launcher aliases.
- Each new in-memory structure read for its lifetime against a vault's.

---

## Summary of Findings

| ID | Description | Severity | Status |
|----|-------------|----------|--------|
| L-1 | The viewer's list and the audio player's queue keep a closed vault's file paths in memory | Low | FIXED, unit-tested |
| L-2 | Any app can send the launcher aliases the "open the player" action | Low | FIXED, device-verified |

No Critical, High or Medium findings.

---

## Findings

### Low

#### L-1: A closed vault's file paths stay in memory

- **Files:** `arcanum/gallery/ViewerOrder.kt` (new in this cycle), `arcanum/gallery/AudioPlayerQueue.kt` (since #139), `arcanum/containers/data/VaultCloser.kt`
- **Description:** `ViewerOrder` holds the list of paths the viewer was opened from; `AudioPlayerQueue` holds the playlist the player was given. Both are process-wide singletons, and nothing cleared either when the vault they belong to was closed - by hand, by auto-lock or by the panic wipe. The paths stayed until the next list replaced them.
- **Impact:** in-process memory only, and only for as long as the process lives. No file contents, only names and locations. Reachable by someone who can read the process's memory (root, a debugger), which is outside what the app can defend - but a closed vault leaving its file list behind is the kind of state that should not outlive its vault.
- **Fix applied:** both gained `clear(containerId)`, which forgets the list only if it belongs to that vault; `VaultCloser.close`, the one path every closing goes through, calls both.
- **Verification:** `ClosedVaultListsTest` - each list survives the closing of another vault and is gone after its own.

#### L-2: The "open the player" action is accepted from any app

- **Files:** `MainActivity.kt` (`handlePlayerIntent`), `arcanum/gallery/service/ArcanumMediaService.kt` (the music session's activity intent)
- **Description:** tapping the music's notification opens the player through an intent carrying `zip.arcanum.OPEN_AUDIO_PLAYER`. `MainActivity` is not exported, but its launcher aliases are, and `handlePlayerIntent` honoured the action from any intent that reached the activity - so any app could start an alias with it, and the player would open after the user's next unlock.
- **Impact:** nothing leaves the app and nothing is learnt by the sender; the player opens only past the lock and only onto a queue from a mounted vault. An app being able to make Arcanum change screen is still a surface it does not need.
- **Fix applied:** the action is honoured only when the intent's component is `MainActivity` itself. The notification's intent names that class directly; an intent through an alias carries the alias's name.
- **Verification:** on a Nothing Phone (1). `am start --activity-single-top -n zip.arcanum/.MainActivityArcanum -a zip.arcanum.OPEN_AUDIO_PLAYER` with music playing - the shell reported the intent delivered to the running instance, and the player did not open, before or after unlocking. A tap on the music's notification still opened it. A first attempt without `--activity-single-top` only brought the task to the front without delivering the intent, so it tested nothing; noted so the check is not repeated that way.

---

## Part-by-Part Notes

### Part 1 — The system log

**Clean, measured.** No logging was added in this cycle. The native layer did not change, so its release build still carries none of the debug format strings counted in September.

### Part 2 — The media sessions

**Clean.** Music and video now have a player and a session each. Both sessions are built on the same `NeutralMetadataPlayer`, so neither reports anything but the neutral title unless **Show what is playing outside the app** is on, and the video's frame is extracted for artwork only in that case. The connection gate in `onGetSession` is unchanged - our own process and system UI only - and only picks which of the two sessions to hand out. Both players are swept when a vault is unmounted. The sessions' ids (`audio`, `video`) are visible in `dumpsys media_session` and say nothing about a vault.

### Part 3 — The notification and the screens it opens

**Clean apart from L-2.** The music session's activity intent is explicit and immutable. What it asks for waits in `PlayerOpenRequest` until the user is past the calculator or the PIN, and is dropped if the vault behind the queue has closed - a notification cannot be used to reach a vault's contents around the lock.

### Part 4 — What is written outside a vault

**Clean.** Links indexed for the gallery go into the same SQLCipher database and encrypted thumbnail cache as everything else; a picture opened through a folder link is resolved to the row of the file it really is, so no second set of rows is made. Search results, the fast scroller's labels and the "in <folder>" lines are drawn on screen and kept nowhere. The new settings (player switches, the music switch, fast scroll) are booleans with nothing of a vault in them. The empty-keyfile notification names no file.

### Part 5 — Regression check against September

INFO-2 of 9 September (the manifest calling `VaultDocumentsProvider` read-only) is closed: the comment now lists what the provider does. INFO-1, INFO-3, INFO-4 and INFO-5 are untouched and still informational. M-1 to M-4 have not regressed: the mount log still records `PIM: custom`, and the backup format did not change.

---

## Release Readiness Assessment

**Verdict: the 1.6.1 changes are safe to ship.**

No Critical, High or Medium findings. Both Low findings are lifetime and surface questions in code written this cycle, both are fixed, L-1 is covered by a test and L-2 was checked on the device.

---

## Conclusion

The cycle added a second media session, a notification that opens a screen, and several things that read paths - and none of it changed what leaves the app. What the audit found was the same shape as in September at a smaller scale: state that outlived its vault (L-1), and a door that was open wider than the one thing meant to use it (L-2).
