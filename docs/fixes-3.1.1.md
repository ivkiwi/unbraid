# unbraid 3.1.1: fix commits for the defects in review-3.1.1.md

This branch (`upstream-fixes` on ivkiwi/unbraid) holds one commit per defect from
[review-3.1.1.md](review-3.1.1.md), each with its tests, on top of v3.1.1 (`f495132`), and one
follow-up to U-08 for a crash found later. This file and review-3.1.1.md are notes, not part of
any fix.

## Taking the commits

- Fetch this branch first. Each commit then applies on its own to `f495132` with a three-way
  merge (`git cherry-pick`, or `git apply --3way` / `git am -3` on its patch). The three-way merge
  needs this branch's objects in your repository: from a patch file alone, or with a plain
  `git apply`, U-03, U-04, U-06, U-10 and U-11 fail for lack of context. The whole series applies
  in order.
- Two dependencies. U-07's tests use `Backup.MAX_EACH` and the merged-route limit from U-10: if
  you take U-07 with its tests, take U-10 first. The U-08 follow-up needs U-08: without U-08's
  read-back of the note, a write it lets fail would switch Tailscale with no note left to undo it.
- U-03 adds an engine patch and changes the committed checksum, not the AAR, which isn't in Git.
  Rebuild `app/libs/libbox.aar` with `tools/build-libbox.sh`. The script rewrites
  `app/libs/libbox.aar.sha256`, so then run `git diff --exit-code HEAD -- app/libs/libbox.aar.sha256`:
  an empty diff means the rebuilt AAR matches the committed checksum. That checksum comes from a
  Linux CI build (Ubuntu 24.04, Temurin JDK 21.0.13+9, NDK 28.2.13676358, Go 1.26.10) and has not
  been reproduced on another machine. If your rebuild differs, compare the toolchain first; if you
  trust your build, commit your own checksum with the patch. U-03's message says the checksum is
  still to be committed; the commit already carries it.

## How far to trust it

- The series went through several rounds of review and revision. It has not been run on a phone.
- CI on a branch made of this series (through the U-08 follow-up) plus a build workflow built the
  engine with U-03's patch, compiled the app and passed every unit test (`testFossDebugUnitTest`,
  restore oracle included). The run reproduced the checksum committed in U-03 and built the APK
  with it.
- Several fixes close the defect as described but leave a narrower case open. The commit messages
  say so, and the "Open" column below summarizes them, so that no fix reads as wider than it is.

## The commits

In series order. "Commit" is the subject line without its trailing defect id; "Open" lists known
limitations, from the commit messages and from the later review.

| Defect | Commit | What changes | Open |
|---|---|---|---|
| U-02 | Direct: an app that shares its user id with one on a VPN route or Block goes as that app does | When a setup is applied, a Direct app that shares a user id with a VPN or Block app gets no rule of its own and is kept inside the VPN; the Apps menu says why. The restore check models the same rule. | User ids are matched only when a setup is applied, and the tunnel rebuilds only when the config text changes. If Android already leaves a Direct app outside the VPN, a VPN or Block app sharing its user id that is installed while connected also goes out directly, unfiltered, until reconnect, while the Apps menu already shows the new routing. Removing the last such app keeps the Direct app inside until reconnect. |
| U-15 | Lockdown: an app set to Direct with the filter off keeps the phone's DNS under a filtered Direct default | Under lockdown, an explicit Direct app with its filter off gets the phone's resolver even when the Direct default filters. | |
| U-04 | DNS: a lookup Android can't name the app of is refused while an app uses a VPN or Block | With an app on Block, a lookup from the phone that no app can be named for is refused before any name rule, whatever the default. With a Direct default and an app on a VPN route, such lookups are refused after the site and app rules (address lookups already got a stand-in; other types reached the phone's resolver). The tailnet's own lookups are exempt. The restore check counts these refusals and normally doesn't report a lifted one at a local network name, with the site-rule exception in "Open". | A local name that also had a site rule can still produce the restore note (see "Left for you to decide", 1). |
| U-13 | Backup: an own server saved without keys takes its config back only from an identical one | A keyless restore gives an own server its config back only if everything the file keeps (plugin, allowed addresses too) matches. | |
| U-10 | Backup: keeping the current setup keeps every own server, one no route uses too | Keep carries every current own server. It isn't offered if the merged setup, as a keyless backup would hold it, has more than 500 routes or more than 500 own servers. | An import writes its server, then its route; a process death between them on a new install leaves the setup "untouched", so a restore offers no Keep and drops that server. Pre-existing. |
| U-07 | Backup: an app still waiting to be installed keeps its choice in the kept setup | Waits are kept per app, route and deadline; expired choices aren't revived by a new restore; the confirm sheet counts waits as of now. | An app installed in time but first seen after a kept choice's deadline loses that choice in Previous (install time isn't known). |
| U-01 | Backup: a failed save never deletes the file it was to replace | The backup is prepared and sealed before the picker opens; it is written only to a document checked to be empty, and the save never deletes the document. | A failed write can leave an empty or partial file. The emptiness check and the write are two steps, so content a sync adds between them is overwritten. |
| U-09 | Pins restored without addresses are told apart by name too, so two AirVPN servers sharing a host name keep their own exits | As the title says. | |
| U-11 | Picker: a restored Nord route naming NordLynx or the standard servers as Nord's API does opens on them, and an unknown choice shows as Other instead of crashing | API names are normalised on both sides. | |
| U-12 | Mullvad-owned servers only moves a running or cached unpinned route off a rented server, or blocks it when none is owned | As the title says. | The relay list can refresh between pick() and install(); the list has no version that install() could compare, so a rented server can run until the next reload. |
| U-14 | Nord: a pin made from Fastest anywhere is looked up in its server's country, so it reconnects after a restore on a new phone | For a pin outside the Dedicated IP group, the exact hostname is looked up in the server's country; only if the country id can't be determined is the full catalogue searched for it. Dedicated IP pins use the account's own list. | |
| U-08 | Speed test: a node test switches back to the node the engine runs, not a saved one it refused | Switch-back uses the running setup, under the service lock, tied to the engine generation the test switched, with a 5 s deadline; an unsure switch-back skips only the Tailscale measurement; a test whose switch-back note can't be written doesn't switch. | After the 5 s deadline the call is cancelled and waited for up to 2 s more; a change the engine already started could still take effect later (an engine-side deadline would close this). Around a reload, a status can be tied to the wrong engine for a moment. |
| U-03 | Engine: a local-network or home rule dials only the addresses it covers, never a name's public ones | sing-box patch `tools/sing-box-address-rule-dials-its-addresses.patch` and the new engine checksum (rebuild the AAR, see above). | |
| U-05 | Unreadable settings: apps stay offline when the engine can't get ready | The hold first puts up a blocking Android interface, with a DNS server inside it, and only then gets the engine ready; if the engine can't get ready, that is logged and the hold goes on. If Android refuses the blocking interface itself and none is up, the start fails as before. When the start that follows a hold fails, the app returns to the hold, keeps the interface it has if Android refuses a new one, and tries again a minute later. | The kept interface may be the engine's own (with Direct apps and excluded networks outside) if a start failed after opening it; the hold then says apps are offline while those still go direct, unless Android blocks connections without VPN. Fix: keep, next to the interface, whether it is the blocking one. |
| U-06 | Restore: the same backup restored again no longer lets a refused edit stop the VPN | The restore number lives in the setup (`Setup.restore`, never saved); a refused edit stops the VPN only for a setup from another restore than the running one, and a restore whose stored setup is what runs counts as running (`Setup.fromRestore`). | If Keep produces a different `Previous` profile and an edit is refused before the engine applies that restore, the VPN still stops, as the rule intends. |
| U-16 | Pause: a timed pause lasts its time when Android restarts the service | The note is written before the engine stops; if it can't be, or the boot count is unknown, the VPN stays on with "Not paused". If the note can't be deleted the app tries to empty it; if both fail, it shows a notice and retries every 30 s. | The retry lives in the process; if Android ends it first, a restart may pause again (the notice says so). |
| U-17 | Network: another Wi-Fi on the same interface retries blocked routes at once | As the title says. | |
| U-18 | Websites: after connect a Headscale tailnet's websites are shown as the tailnet's | As the title says. | |
| U-08 follow-up | Speed test: a switch-back note that won't read or write no longer ends the app | `Store.tsTestFrom` no longer lets an `IOException` out. The speed test reads and writes it in tasks posted to its executor with no catch and no handler, so an `IOException` reading or writing the note ended the app's process, the VPN service with it; the first read and the write were already there in 3.1.1. A note that exists but won't read now counts as a switch still to undo with no node known, so the switch back uses the routed node (or none) if Tailscale knows it, else asks Tailscale for no exit node and tries again on the next status; a write that fails leaves the note as it was (none, before a node test), so the test doesn't switch. | A note that won't delete is tried again on each Tailscale status, as before. |

## Left for you to decide

Found in the last review rounds and not fixed here, as they are minor or would need more than a
small change:

1. **U-04, restore check wording.** With LAN on and both an app and a site such as `printer.lan`
   blocked before a restore that lifts both, `place()` finds the old site rule in the union of old
   and new rules, so `who()` still adds the "traffic Android can't tie to an app ... goes Direct"
   note. A fix should identify the built-in LAN or tailnet rule that answered the lookup; the
   resolver tag alone isn't enough, as an explicit Direct website can use the same `local` tag.
   The restore oracle probes LAN names but gives them no site rules and generates no tailnet
   names, so it doesn't cover this.
2. **U-07, confirm sheet.** If a waiting choice is the only reason the merged setup exceeds 500
   routes, its expiry alone doesn't refresh the sheet, so Keep stays hidden; reopening the sheet or
   changing the current setup reruns the check.
3. **U-02, full fix.** Watching installs and removals against the running setup and rebuilding the
   tunnel when the user ids it actually leaves out change (see the table).
4. **U-05, full fix.** Tracking whether the kept interface is the blocking one (see the table).
