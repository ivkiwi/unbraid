# unbraid 3.1.1: defects found in a review, with suggested fixes

**Target:** `quiet-crafts/unbraid` at `v3.1.1` = `f495132341a66825a8c9372cec380999f8842762` (sing-box 1.14.4 with the repo's `tools/sing-box-*.patch`).
**Audience:** whoever fixes these in unbraid. Every path and line below refers to that commit.

## How this list was made, and how much to trust it

- Three independent reviewers read the release, each covering one area: the engine and VPN lifecycle, data and providers, and routing rules and the UI. Each followed call paths through the Kotlin app and, where it mattered, into the sing-box 1.14.4 sources.
- **All findings are static.** No Android build, device run or traffic capture was made. The code paths were traced, and some Go tests of the engine patches were run on a desktop host. Nothing below was reproduced on a phone.
- **Before fixing anything, reproduce it with a unit test first.** Most items name the test that would show the bug. Several fit the existing harnesses: `RoutingProbe`, `OlderBackupsTest`, `BackupAdversarialTest`, `FakePoolTest`, `WidgetGlanceTest` and `CompilerTest`.
- **Severity:**
  - **P1:** traffic leaves the VPN or the chosen route; data is lost; a crash; or a security/privacy promise breaks.
  - **P2:** wrong behaviour a user can hit, without a leak or loss.
- The README and commit history make unusually precise promises. Several items are bugs because they break one of those promises, and that promise is quoted where it applies.
- Items the history shows are intended were dropped. Example: commit `4c015dc` keeps the not-answering list cleared on Disconnect.

## Index

| ID | Sev | Area | Summary |
|---|---|---|---|
| U-01 | P1 | Backup (UI) | A failed save deletes the previous backup the user chose to overwrite |
| U-02 | P1 | Compiler / VPN | Direct on one package takes every package sharing its Android UID out of the VPN |
| U-03 | P1 | Compiler / engine | One LAN address in a DNS answer sends the name's public addresses Direct too |
| U-04 | P1 | Compiler / engine | A lookup whose owner Android can't tell may reach the system resolver |
| U-05 | P1 | VPN lifecycle | The offline hold for unreadable settings depends on a disk write |
| U-06 | P1 | VPN lifecycle / Backup | Restoring the same backup twice, then any refused edit, tears down a healthy VPN |
| U-07 | P1 | Backup | A second restore drops a waiting app's Block from the saved `Previous` profile |
| U-08 | P1 | Speed test | After a Tailscale node test, apps exit through a node the engine had refused |
| U-09 | P1 | LinkPicker / AirVPN | Two pinned AirVPN servers sharing a hostname collapse into one exit after a restore |
| U-10 | P1 | Backup | "Keep my current setup" silently drops an own server that no route uses, with its key |
| U-11 | P1 | Picker (UI) | Nord's location picker crashes on protocol/group values that a restore accepts |
| U-12 | P2 | LinkPicker / Mullvad | "Mullvad-owned servers only" doesn't move a running route off a rented server |
| U-13 | P2 | Backup / Shadowsocks | A keyless restore can drop a Shadowsocks server's required plugin |
| U-14 | P2 | Nord | A pin made from "Fastest anywhere" can't reconnect after a restore on a new phone |
| U-15 | P2 | Compiler (lockdown) | Under lockdown, an app with its filter turned off still gets the Direct filter |
| U-16 | P2 | VPN lifecycle | A one-hour pause can end after a minute when Android restarts the service |
| U-17 | P2 | Net | Moving to another Wi-Fi on the same interface skips the promised retry |
| U-18 | P2 | VPN state / Websites | After connect, a Headscale tailnet's websites are shown as on their VPN route |

---

## P1

### U-01: A failed backup save deletes the previous backup

- **Where:**
  - `app/src/main/java/app/unbraid/ui/Backup.kt:218` and `:238` (`saveTo()`).
  - Existing test of the refused-setup path: `app/src/test/java/app/unbraid/BackupAdversarialTest.kt:377`.
- **Scenario:**
  1. The user saves a backup and, in the system picker, chooses an existing file to replace. With "replace", AOSP DocumentsUI returns the URI of that existing document (`picker/ActionHandler.java`).
  2. Something fails before the new bytes are written. Two known triggers:
     - `Backup.readBack()` refuses the current setup. There is already a test for a setup it refuses.
     - The process lost the passphrase while the picker was open (process death).
  3. `saveTo()` then calls `deleteDocument(uri)` on the returned URI. The user's previous, good backup is deleted, although no output stream was ever opened.
- **Fix:**
  - Build, check (`readBack`) and encrypt the backup bytes **before** launching the picker.
  - After the picker returns, only open the stream and write.
  - Never delete the returned URI unconditionally. If a "created a new empty document" case must be cleaned up, delete only a document the app itself created in this flow, which needs a way to know it was created.
- **Test:** a fake `ContentResolver` holding an existing document; make `readBack` throw; assert the document's bytes are unchanged.

### U-02: Direct on one package takes every package sharing its UID out of the VPN

- **Where:**
  - `app/src/main/java/app/unbraid/vpn/Compiler.kt:273` (packages excluded at the OS level).
  - `app/src/main/java/app/unbraid/vpn/UnbraidVpn.kt:956` (`blockedKin`).
  - `UnbraidVpn.kt:1195` (where the exclusion is handed to Android).
- **Scenario:**
  1. Two installed packages share a Linux UID (`android:sharedUserId`). Real examples:
     - `com.google.android.gms` and `com.google.android.gsf` (`com.google.uid.shared`);
     - system groups such as `android.uid.system` and `android.uid.phone`;
     - some vendor suites.
  2. Package A is on a VPN route; package B is Direct; lockdown is off.
  3. The compiler puts B in `exclude_package`. Android's VPN exclusion works per UID, so A's traffic never enters the TUN, and no sing-box rule can route it.
  4. The code already handles this for **Block** (`blockedKin` keeps a blocked app's UID siblings inside), but not for an ordinary VPN route next to a Direct sibling.
- **Fix:**
  - Before excluding a package, look at its whole UID group (the same lookup `blockedKin` uses).
  - If any sibling is on a route other than Direct, don't exclude the UID; keep it inside the TUN. Note that inside the TUN the engine knows a connection's UID, not which sibling opened it, so per-package rules for siblings are only as good as the package lookup for that UID. The honest model is "one UID = one app": give the whole group one route.
  - Show the conflict in Apps ("shares its network identity with X: kept inside the VPN"). Lookup failures should fail closed (keep inside), as the Block path does.
- **Test:** a kin lookup returning `{A, B}`, with A on route `r` and B Direct; assert neither appears in `exclude_package` (or `route_exclude`) and that per-package rules route each.

### U-03: One LAN address in a DNS answer sends the name's public addresses Direct

- **Where:**
  - `app/src/main/java/app/unbraid/vpn/Compiler.kt:377` (the LAN/home `ip_cidr` rules after resolving).
  - sing-box `route/rule/rule_item_cidr.go:94`: an address rule matches if **any** destination address matches.
  - sing-box `route/conn.go:109`: the outbound then dials with **all** resolved addresses.
- **Scenario:**
  1. The app is on a VPN route, and "Local network bypasses the VPN" is on.
  2. A name resolves to both a private address (e.g. `192.168.1.10`) and a reachable public IPv4.
  3. The LAN rule matches on the private one, and the Direct outbound gets the whole list. The TCP connect can land on the **public** address outside the VPN.
  4. The same applies to the home-tunnel CIDR rules: public addresses would enter the home tunnel.
  5. `RoutingProbe` (`app/src/test/java/app/unbraid/RoutingProbe.kt:72`) resolves every name to one address, so it can't see this.
- **Fix:** for routes chosen by an address rule, dial only the addresses that rule matched. Two ways:
  - an engine patch: after an `ip_cidr` match on a resolved destination, filter `metadata.DestinationAddresses` to the matching ones before handing them to the outbound;
  - a resolve strategy that splits mixed answers.

  Apply the same to the home-tunnel rules.
- **Test:** extend `RoutingProbe` with a resolver returning `[192.168.1.10, 203.0.113.5]` for an app on a VPN route with the LAN bypass on; assert `203.0.113.5` is never dialled Direct.

### U-04: A lookup whose owner Android can't tell may reach the system resolver

- **Where:**
  - `app/src/main/java/app/unbraid/vpn/Compiler.kt:235` (DNS rules and `dns.final`).
  - The connection-side guard for an unknown owner: `Compiler.kt:387`.
  - sing-box `route/process_cache.go:48` (a UID lookup error is logged, and processing continues).
  - sing-box `route/dns.go:63` (the query is passed on to DNS routing).
- **Scenario:**
  1. The default route is Direct, the DNS filter is off, and an app is on a VPN route.
  2. The app asks a TXT (or another non-A/AAAA) question for an ordinary internet name that no website rule covers.
  3. Android fails to report the owner, so package rules don't match. TXT skips the fake-IP rule (`query_type: A, AAAA`), and `dns.final` is `local`. The query goes out through the phone's own resolver, outside the app's VPN route.
  4. Connections from an unknown owner are already guarded. DNS is handled before that guard.
- **Fix:** for queries from the TUN inbound with no resolved owner, don't fall through to an outside resolver while route choice depends on the package. Refuse them, or answer them through the default route's resolver only when that route is not Direct. A rule matching "no package" (or a small engine flag on metadata) before `final` would do it.
- **Confidence:** the code path is confirmed. How often owner lookup fails on real devices was not measured.

### U-05: The offline hold for unreadable settings depends on a disk write

- **Where:**
  - The promise: `README.md:175`.
  - `app/src/main/java/app/unbraid/vpn/UnbraidVpn.kt:477`: the fake-IP block number is saved **before** the TUN is established.
  - The catch-all path: `UnbraidVpn.kt:456`.
- **Scenario:**
  1. A fresh start finds the settings unreadable, so `hold()` must keep apps offline, as the README promises.
  2. Before establishing the TUN, the start saves the fake-IP block number. If that write fails (full storage, I/O error), the exception reaches the generic `catch`, which calls `teardown`.
  3. No blocking interface is left. Without Android's own "Block connections without VPN", apps go out over the plain network.
- **Fix:** `hold()` should establish a minimal blocking TUN that drops everything and needs neither `fakeblock` nor `cache.db`. Keep it until a working TUN replaces it. Errors while preparing the real engine must never tear the hold down.
- **Test:** make the fake-block save throw while held; assert a TUN is still established, or teardown is not called.

### U-06: Restoring the same backup twice, then any refused edit, tears down a healthy VPN

- **Where:**
  - `app/src/main/java/app/unbraid/ui/Backup.kt:282` (a restore increments `restores`).
  - The reload condition: `app/src/main/java/app/unbraid/vpn/UnbraidVpn.kt:450`.
  - The wrong teardown: `UnbraidVpn.kt:735`.
- **Scenario:**
  1. The VPN is running. The user restores one backup that has no per-app choices, with "save current as Previous" off.
  2. They restore the same backup again. `restores` increments, but the resulting `Setup` equals what already runs.
  3. The watcher only reloads on a change of setup or accounts, so `ranRestores` stays behind.
  4. The next ordinary edit that `checkConfig` refuses reaches the failure path. The old engine is still healthy, but the counter mismatch calls `teardown(RESTORE_REFUSED)`.
- **Fix:** pass the restore's generation with its result. When the restored setup and accounts are already the running pair, acknowledge the new generation under `lock`: set `ranRestores` without a reload.
- **Test:** a service-model test: restore, restore the identical setup, then a refused edit; assert phase stays On.

### U-07: A second restore drops a waiting app's Block from the saved `Previous` profile

- **Where:**
  - `app/src/main/java/app/unbraid/data/Backup.kt:522` (`restoredFrom`).
  - Creating `Previous`: `Backup.kt:561`.
  - Pruning: `app/src/main/java/app/unbraid/ui/Ui.kt:103` (`keepKnown()`).
  - The default route choice: `app/src/main/java/app/unbraid/data/Model.kt:422`.
- **Scenario:**
  1. After a first restore, a not-yet-installed app waits (up to 30 days) with its rule **Block**.
  2. Within that window the user restores another backup without that package and chooses "Keep my current setup". The rule is copied into `Previous`, but the package is no longer in `waiting`.
  3. The next app-list refresh (`keepKnown()`) deletes the rule from `Previous` as unknown.
  4. The user installs the app and switches to `Previous`, whose default is Direct. The app goes out Direct instead of being blocked.
- **Fix:** when `Previous` is saved, carry over the still-valid waiting entries (with their deadlines) for the packages it holds, so `keepKnown()` keeps them.
- **Test:** chain two `restoredFrom()` calls, one `keepKnown()`, and a switch to `Previous`; assert the package is still Block.

### U-08: After a Tailscale node test, apps exit through a node the engine had refused

- **Where:**
  - `app/src/main/java/app/unbraid/stats/SpeedTest.kt:170` and `:415` (`switchBack()`).
  - Handling of a refused config: `app/src/main/java/app/unbraid/vpn/UnbraidVpn.kt:723`.
- **Scenario:**
  1. The engine runs Tailscale exit A. Settings hold B: that edit was refused, so the service kept the last good config with A.
  2. The user speed-tests node C.
  3. Afterwards, `switchBack()` reads B from `store.setup`, sets it on the running Tailscale, and on success clears the restore marker. Apps on the Tailscale route now exit through B, which the user's last applied state never ran.
- **Fix:** remember the exit node from `UnbraidVpn.running` for the current engine generation before the test, and switch back to exactly that. If the engine restarted meanwhile, don't switch at all.
- **Test:** a model test with running = A, saved = B, test node C; assert the node after the test is A.

### U-09: Two pinned AirVPN servers sharing a hostname collapse into one exit after a restore

- **Where:**
  - `app/src/main/java/app/unbraid/vpn/LinkPicker.kt:32` (cache key).
  - `LinkPicker.kt:137` (taking a server from the cache).
  - `LinkPicker.kt:80` (a server no longer offered).
  - `app/src/main/java/app/unbraid/data/Backup.kt:360` (`Backup.bare()`).
- **Scenario:**
  1. In upstream's own fixture, `Aladfar` (Miami) and `Aquila` (Fremont) have different addresses but the same hostname, `us.vpn.airdns.org`. The user pins both from one country list.
  2. A restore on a new phone keeps the names and clears the IPs (`bare()`).
  3. On the first connect both resolve correctly, but they share a cache key, so the last write wins.
  4. On the next connect, `fresh()` checks only the hostname and gives both routes the same server. Traffic stays inside AirVPN, but leaves through the other pinned location.
  5. The "no longer offered" handling has the same problem: one server disappearing blocks its healthy namesake.
- **Fix:** include the server's name (or a stable server id) in the pin identity and the cache key, and check it when taking a server from the cache. Do the same in the `NoLongerOffered` propagation.
- **Test:** two pins with the same hostname and different names, after `bare()`; resolve both twice; assert distinct servers.

### U-10: "Keep my current setup" silently drops an own server that no route uses

- **Where:**
  - `app/src/main/java/app/unbraid/data/Backup.kt:538`.
  - The confirmation text: `app/src/main/java/app/unbraid/ui/Backup.kt:384`.
  - The existing test, which gives the spare server a route: `app/src/test/java/app/unbraid/BackupTest.kt:241`.
- **Scenario:**
  1. The user added a spare WireGuard server in Settings, not yet assigned to any route.
  2. They restore someone else's backup with "Keep" on. The sheet lists the replaced profiles, favourites and settings.
  3. `restoredFrom()` only carries over servers referenced by kept routes. The spare server and its private key disappear, unannounced.
- **Fix:** keep unreferenced own servers, using the existing ID-conflict handling. Or list the servers that will be removed in the confirmation, before the user confirms.
- **Test:** a phone with an unassigned own server, restore with Keep; assert the server is kept (or listed in the sheet).

### U-11: Nord's location picker crashes on values that a restore accepts

- **Where:**
  - `app/src/main/java/app/unbraid/ui/PickerScreen.kt:248`: an unconditional `first { … }`.
  - The option lists: `PickerScreen.kt:93`.
  - Backup reading: `app/src/main/java/app/unbraid/data/Backup.kt:339`.
  - `app/src/main/java/app/unbraid/data/Nord.kt:36`.
- **Scenario:**
  1. An accepted backup has a Nord route with `protocol = "wireguard_udp"`. The engine understands it, but the UI list represents NordLynx as `null`.
  2. The user is signed in to Nord and opens *Change location*. The app crashes with `NoSuchElementException`.
  3. The group list has the same issue with `legacy_standard`.
- **Fix:**
  - Normalise known equivalents when the screen opens: `wireguard_udp` → `null` (NordLynx), plus the matching `legacy_*` group ids.
  - Use `firstOrNull`. For an unknown saved value, show it as "Other (saved)" and let the user pick a replacement.
  - Alternatively, normalise at `Backup.read`.
- **Test:** open the picker model with those values; assert no exception and a selected NordLynx.

---

## P2

### U-12: "Mullvad-owned servers only" doesn't move a running route off a rented server

- **Where:**
  - `app/src/main/java/app/unbraid/vpn/LinkPicker.kt:70` (the running tunnel is kept).
  - The fallback: `LinkPicker.kt:142`.
  - The existing client-only test: `app/src/test/java/app/unbraid/MullvadTest.kt:128`.
- **Scenario:**
  1. An unpinned route sits at `us-lax`; in the fixture, all three active relays there are rented.
  2. The user turns the setting on. `reload()` passes the old tunnel to `LinkPicker`; location and account are unchanged, so the tunnel stays.
  3. Even after a full reconnect, finding no owned relay throws an `IOException`, after which `fresh()` falls back to the cached (rented) server.
- **Fix:**
  - Apply the ownership constraint to the running tunnel and to the cache of unpinned routes.
  - With no confirmed owned relay, block the route ("no Mullvad-owned server here"); never fall back to a rented one.
- **Test:** a cached and running rented relay, setting turned on; assert the route is re-picked or blocked.

### U-13: A keyless restore can drop a Shadowsocks server's required plugin

- **Where:**
  - `app/src/main/java/app/unbraid/data/Model.kt:144` (`sameAs()`).
  - `app/src/main/java/app/unbraid/data/Backup.kt:514`.
  - The TCP-only switch keyed on the plugin: `app/src/main/java/app/unbraid/vpn/Compiler.kt:158`.
- **Scenario:**
  1. The backup holds an own server with `obfs-local`.
  2. On the phone, the server with the same ID, name, host, port and cipher no longer has the plugin.
  3. `sameAs()` treats them as equal, so the restore unblocks the route and copies the local config whole. The plugin requirement is lost, and UDP behaviour changes too: the TCP-only switch depends on the plugin's presence.
- **Fix:** compare every field `withoutSecrets()` keeps, plugin and plugin options included, also when the IDs match. The neighbouring path for a route without `own` already compares strictly.
- **Test:** a file server with a plugin and a phone server without one, same id; assert the route stays keyless/blocked.

### U-14: A pin made from "Fastest anywhere" can't reconnect after a restore on a new phone

- **Where:**
  - Pin creation: `app/src/main/java/app/unbraid/ui/Ui.kt:664` ("Use … for both routes").
  - `app/src/main/java/app/unbraid/data/Nord.kt:53` and `:61`.
- **Scenario:**
  1. The button saves the actual server as a pin, but the source route has no `countryId`.
  2. After a restore on a new phone, the pin has its hostname and an empty IP.
  3. `Nord.connect()` skips the server-list request because `countryId == null`, and `Addr.usable()` then refuses the empty IP. Both routes stay blocked until the user picks again by hand, even signed in and online.
- **Fix:** for a saved pin without a country, look its hostname up in Nord's trusted catalogue (or the recommendations API by hostname) to get its address. Alternatively, save the `countryId` when the pin is created.
- **Test:** a pinned route with `countryId = null` and an empty IP, with a catalogue stub; assert it connects.

### U-15: Under lockdown, an app with its filter turned off still gets the Direct filter

- **Where:**
  - `app/src/main/java/app/unbraid/vpn/Compiler.kt:169`: per-app groups with `id == defaultRoute` are dropped.
  - The promise: `app/src/main/java/app/unbraid/ui/SettingsScreen.kt:552`.
  - `filterScopes` tests: `app/src/test/java/app/unbraid/CompilerTest.kt:196`.
- **Scenario:**
  1. The filter is on for apps following the Direct default, and Android lockdown is on.
  2. One app is explicitly Direct with *Filter its lookups* off. Settings promise it the phone's DNS.
  3. Its own rule is dropped because its route equals the default, so its lookups go to `dns-filter`. The personal DNS provider sees its names and may block domains it needs.
  4. The existing test checks the two settings separately, never together.
- **Fix:** under lockdown, keep a separate `local` DNS rule for apps that are explicitly Direct and unfiltered, even when Direct is the default.
- **Test:** add the combination to `filterScopes`.

### U-16: A one-hour pause can end after a minute when Android restarts the service

- **Where:**
  - `app/src/main/java/app/unbraid/vpn/UnbraidVpn.kt:347`: `ACTION_PAUSE` returns `START_STICKY`.
  - `UnbraidVpn.kt:377`: `handle(null)` → `connect()`.
- **Scenario:**
  1. Always-on is off, and the user pauses for an hour.
  2. Android kills the process and later recreates the service with a `null` intent, as `START_STICKY` does when no commands are pending.
  3. The new process has lost the `Paused` state. `handle(null)` connects and cancels the alarm, so the pause ends early.
- **Fix:** persist the pause deadline as `elapsedRealtime` plus the boot count. On a `null` intent, if the same boot is still before the deadline, stay paused and re-arm the alarm.
- **Test:** simulate a `null`-intent start with a stored future deadline; assert phase Paused.

### U-17: Moving to another Wi-Fi on the same interface skips the promised retry

- **Where:**
  - New network: `app/src/main/java/app/unbraid/vpn/Net.kt:69`.
  - The callback that is skipped: `Net.kt:83`.
  - The retry timer: `app/src/main/java/app/unbraid/vpn/UnbraidVpn.kt:584`.
- **Scenario:**
  1. A provider was unreachable, so the service scheduled a retry up to five minutes away and promises to retry at once on a network change.
  2. The user moves quickly to another Wi-Fi. Android gives a new `Network` on the same `wlan0` with the same index.
  3. `onAvailable()` sees it, but `InterfacePush` drops an identical interface, so `onChange` never runs. With no other reason to reload, the route waits for the old timer.
- **Fix:** trigger the network-change retry on a change of `Network.networkHandle`. Keep the separate name/index comparison for the engine notification only.
- **Test:** a `Net` model test: two different handles with the same interface name and index; assert `onChange` fires.

### U-18: After connect, a Headscale tailnet's websites are shown as on their VPN route

- **Where:**
  - The suffix is written: `app/src/main/java/app/unbraid/vpn/UnbraidVpn.kt:547` (`apply()` → `state.tsSuffix`).
  - It is lost on the move to On: `UnbraidVpn.kt:430`, where `connect()` creates a new `VpnState(On, …)` with `tsSuffix = null`.
  - Applicability: `app/src/main/java/app/unbraid/data/Sites.kt:88`.
- **Scenario:**
  1. The user's own tailnet domain is saved and allowed. The engine compiles it into DNS rules, so names under it go to the tailnet.
  2. The UI state loses the suffix at connect. When Headscale reports the same domain, nothing changes, so there is no reload and the state is never refilled.
  3. The website list and the widgets count a website under that domain as applied on its VPN route, while the engine sends it to the tailnet.
- **Fix:** publish `tsSuffix = lastInput?.tsSuffix` with the first `On` state (or carry it over from the previous state).
- **Test:** connect with an allowed Headscale suffix; assert `state.tsSuffix` equals the compiled one and `Sites.applied(…, reported = it)` leaves the website out.

---

## Suggested order

1. **Leaks and loss first:**
   - U-01 (data loss; small, contained fix).
   - U-02 (shared UID leak; reuse the `blockedKin` lookup).
   - U-05 (hold must not depend on a write).
   - U-03 (needs an engine patch; check `RoutingProbe` first).
   - U-04.
2. **Then the other P1s:**
   - U-11 (crash; one-line `firstOrNull` plus normalisation).
   - U-08, U-06, U-07, U-10, U-09.
3. **The P2s in any order.** U-18 and U-16 are small.
