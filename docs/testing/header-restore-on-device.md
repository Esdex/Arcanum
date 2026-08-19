# Header restore: what still needs a device

The refusal added in #147 - a header restore is turned away while that volume is
mounted - is verified by review and by both ABIs compiling, and by nothing else.
Its positive case needs a mounted volume, which no host stand can produce. These
are the steps that close it.

## Why it matters enough to test by hand

A restored header can name a different master key. A mounted drive keeps the keys
it derived when it was mounted, so everything written after the restore is
encrypted with the old ones while the header on disk describes the new. Nothing
says so at the time; it surfaces at the next mount, as data that will not decrypt.

The risk of the guard itself is the mirror image: this is a **recovery** path, used
when something is already wrong, and a refusal that fires when it should not is
worse than no refusal at all. Scenarios 1 and 2 below are there for that, and they
matter more than scenario 3.

## Before starting

- Use throwaway vaults. Scenario 3 in particular ends with a deliberately damaged
  one.
- Make a header backup of each test vault first (Vault details -> Back up header),
  because that backup is what the restore then puts back.
- Watch the log while testing:

      adb logcat -s ArcanumNative

  The guard logs `restore refused: that volume is mounted right now` and returns
  `ERR_BUSY` (-14). Its absence is as informative as its presence.

## Scenario 1 - the ordinary restore still works

The regression that matters. If the guard misfires, this is where it shows.

1. Create vault A. Back up its header. Do **not** mount it.
2. Restore the header from that backup.
3. Expected: it succeeds. No `restore refused` line in the log.
4. Mount A afterwards and open a file, to confirm the volume is still usable.

A failure here means the guard is refusing something it must not - the fstat
comparison is matching when it should not - and it is the one outcome that should
stop the release.

## Scenario 2 - a *different* vault is mounted

This is the case the guard has to get right and the UI cannot check: the screen
only looks at the vault whose header is being restored.

1. Create vaults A and B, back up B's header.
2. Mount **A** and leave it mounted.
3. Restore **B**'s header while A is still mounted.
4. Expected: it succeeds. No `restore refused` line. Matching is by device and
   inode, so another vault being open is irrelevant.

A refusal here means the comparison is too broad - it would be matching something
other than the file itself.

## Scenario 3 - the same vault is mounted (the guard firing)

Reaching the native guard needs the UI check out of the way, because
`RestoreHeaderViewModel.startRestore` refuses first with "Unmount the vault before
restoring its header". That message appearing is itself worth confirming (step 1),
but it is the first layer, not the one under test.

1. Mount vault A, then try to restore its header. Expected: the screen refuses with
   the message above, and there is no `restore refused` line - the request never
   reached native.
2. To exercise the second layer, build a debug APK with the ViewModel check
   commented out:

       // if (repo.getContainerHandle(containerId) != null) { ... return }

   in `RestoreHeaderViewModel.startRestore`.
3. With that build: mount A, restore A's header.
4. Expected: the restore fails, the log carries `restore refused: that volume is
   mounted right now`, and the screen shows `BUSY` - that screen renders the error
   enum's name, which is the existing pattern there rather than something this
   change introduced.
5. Confirm the vault is undamaged: unmount, mount again, open a file. The refusal
   happens before anything is written, so the volume must be exactly as it was.
6. Discard that build.

## Not covered by any of this

A USB-hosted volume arrives as a transport with no descriptor to compare, so the
native guard cannot run for it and the ViewModel remains the only layer. Scenario 3
step 1 is the whole of the check there.
