# ZDT-D non-root version

Goals (approved by the user):

1. **One APK, auto-detection.** The app detects whether the ZDT-D module / root is
   available. If it is, the existing root engine is used and nothing changes.
   If root is not available, the app switches to the built-in non-root engine.
2. **All engines that are technically possible without root.**
3. **Full profile/settings compatibility** with the root version: the same
   `active.json` / `setting.json` layout, the same backup (`.zdtb`) format.

## Why some features cannot work without root

| Feature | Root-only? | Reason |
|---|---|---|
| NFQUEUE (`nfqws` / `nfqws2`) | **yes, hard** | Needs `iptables -t mangle ... -j NFQUEUE`. `VpnService` cannot intercept sockets created by other apps at the packet level. |
| `iptables`/`nftables` REDIRECT / TPROXY | **yes** | Requires NET_ADMIN. |
| `netd` UID→network binding (`vpn_netd`) | **yes** | Requires `Network#bindSocketToNetwork` from the system; only the root daemon can do this. |
| `/dev/tun` interface creation (`-device tun://...`) | **yes** | Needs `CAP_NET_ADMIN`. |
| Per-app routing | **no** | `VpnService.Builder.addAllowedApplication()` covers this. |
| TUN→SOCKS (`tun2socks`) | **no** | Works if the TUN fd is provided by `VpnService.establish()`. |
| sing-box / mihomo in TUN mode | **no** | Same: they accept a fd from `VpnService`. |
| byedpi, dnscrypt-proxy, hysteria2, wireproxy, tor, mieru | **no** | Plain userspace daemons listening on 127.0.0.1. |

So the non-root version = **`VpnService` + userspace engines**. The DPI bypass
that is lost is only the NFQUEUE part; the SOCKS5/VPN engines keep working.

## Autonomous operation (no daemon required)

The app never depends on the daemon being reachable to stay usable:

- **Mode selection** happens before any daemon call (`RootAvailability.detect`),
  so a device without the module goes straight to the userspace engine.
- **Fallback offer.** When the module *is* installed but its service never
  answers, the startup screen and the runtime "service unavailable" dialog both
  offer **Continue without module** (`switchToNonRootFallback`). It activates
  the same userspace runtime as the first-run mode selector and records the
  choice in `preferred_runtime_mode`, so the app keeps working autonomously
  instead of sitting on a dead screen.
- **Profile management without the daemon.** In non-root mode the app-private
  `working_folder` mirror is the source of truth, so `NonRootProfiles` builds
  the same `ApiModels.Program` list the daemon would have returned
  (`listPrograms`), and create / enable / delete operate on the local files
  (`createProfile`, `setProfileEnabled`, `deleteProfile`). The profile screens
  therefore work identically with no daemon running.
- **Profile editors without the daemon.** Every editor screen talks in daemon
  API paths (`/api/programs/<p>/profiles/<n>/setting`, `.../proxy`,
  `.../apps/user`, `.../config`, `.../servers/<s>/setting|config`). In
  non-root mode `NonRootProfiles.fileForApiPath` resolves those paths against
  the same file tree the daemon uses, so `loadJsonData` / `saveJsonData` /
  `loadText` / `saveText` read and write the local mirror instead of the
  network. The server list (`.../servers`) is synthesized from the on-disk
  `server/<name>/setting.json` entries, the profile list (`.../profiles`) from
  `active.json`, and per-server create / delete (`createServer` /
  `deleteServer`) manage those directories locally.
- **Custom programs (`myprogram`) without the daemon.** The binary list
  (`.../bin`), upload and delete operate on the profile's local `bin/`
  directory, so user-supplied engines keep working with no daemon.
- **mihomo without the daemon.** The runtime config is built exactly like the
  daemon's `prepare_runtime_config`: the user's `config.yaml` is sanitized
  (the `tun`/`iptables` blocks and the port/bind/log scalars ZDT-D manages are
  dropped) and a managed header is prepended with the profile's `mixed_port`,
  `log_level` and controller port, then the VpnService `tun.file-descriptor`
  block is appended. mihomo also gets the same home directory (`<profile>/work`)
  the daemon gives it, so its cache lives next to the profile instead of in the
  service's working directory.
- **Strategy variants (byedpi) without the daemon.** `listStrategicVariants`
  and `applyStrategicVariant` operate on the local strategy directory and
  write the chosen strategy into the profile's `config/config.txt`, the same
  file the userspace byedpi engine reads. `nfqws`/`nfqws2`/`dpitunnel` have no
  userspace engine (NFQUEUE needs root), so their strategy lists come back
  empty rather than erroring.
- **Status** is synthesized locally (`NonRootStatus.report`) from the tunnel
  state, so Home, the Quick Settings tile and the widgets keep rendering.

## Architecture

```
MainViewModel
   ├─ RootAvailability.detect()  (module.prop / token / su probe)
   │
   ├─ root mode  → ApiClient → zdtd daemon (unchanged)
   │
   └─ non-root mode
         → NonRootEngine (Kotlin, app process)
              → reads the same profile layout from the app-private dir
              → VpnEngineService (foreground, establishes TUN fd)
                   → spawns engine binary with the fd
                             mihomo    -f config.yaml   (tun.file-descriptor: %FD% in the YAML)
                             tun2socks -device fd://<N> -proxy socks5://127.0.0.1:<port>
                         → sing-box vpn mode is a *pair* (mirrors singbox.rs):
                             sing-box run -c cfg   (mixed inbound only, no tun)
                             ... wait for the socks port ...
                             tun2socks -device fd://<N> -proxy socks5://127.0.0.1:<port>
```

### How each engine receives the TUN descriptor

Verified against the shipped sources (this is the crux of the non-root backend):

| Engine | fd syntax | Why |
|---|---|---|
| `tun2socks` | `-device fd://<N>` | `main.go` maps `-device` to `engine.parseDevice`; the `fd://` scheme reaches `fdbased.Open`, which does `strconv.Atoi` on the host part. A bare `-fd <N>` is **not** a flag. |
| `sing-box` | **cannot** take a fd from its JSON config | `option/tun.go` has no `file_descriptor` field; that field exists only on sing-tun's internal `Options`, which libbox (the Android graphical client) sets in-process. So sing-box runs as a socks server and `tun2socks` holds the fd — exactly the daemon's `normalize_singbox_config_for_t2s` + `spawn_tun2socks_for_vpn` pair. |
| `mihomo` | `tun.file-descriptor: <N>` in the YAML | `config.RawTun.FileDescriptor` → `listener/sing_tun/server.go`. Requires `auto-route: false` and `auto-detect-interface: false`, because `VpnService` already owns the interface and routing. |

The fd only exists after `VpnService.establish()`, so every placeholder is
resolved at launch time in `VpnEngineService`: `%FD%` in argv (tun2socks) and
in the written config file (mihomo).
directory so that `.zdtb` backups and manual profile copies work both ways:

```
<filesDir>/working_folder/<program>/active.json
<filesDir>/working_folder/<program>/<profile>/setting.json
<filesDir>/working_folder/<program>/<profile>/app/uid/user_program
<filesDir>/working_folder/<program>/<profile>/log/*.log
```

## Implementation steps

1. Manifest: `BIND_VPN_SERVICE` permission + `VpnEngineService` declaration
   (foregroundServiceType `specialUse`, since this is not a "vpn" type in the
   classic sense — the TUN is local-only).
2. `RootAvailability`: probe root. Cached, re-checked on process death.
3. `NonRootProfiles`: read/parse the same JSON layout as the daemon.
4. `NonRootBinaries`: install bundled engine binaries from APK assets to
   `no_backup/bin`, mirroring `DpiDetectorBinary` (SHA-256 verified, atomic).
5. `VpnEngineService`: `VpnService` that establishes the TUN, keeps the fd
   alive, and dies if the engine process dies (VpnService lifetime == engine
   lifetime).
6. `NonRootEngine`: orchestrates start/stop, per-app list, status.
7. `MainViewModel` integration: `toggleService()` dispatches on root mode.
8. Status reporting: non-root mode fills `ApiModels.StatusReport` locally so
   the existing UI (Home, tile, widgets) works unchanged.

## What is deliberately NOT changed

- the root flow, the daemon, `api.rs`, `runtime.rs`, `vpn_netd.rs`,
  `iptables/**`, the module, `build.sh`, the release workflow;
- the package name / applicationId;
- existing UI screens (they consume the same `UiState`).

## Status of this work

### Phase 1 — done (this change)

Auto-detection + the full non-root plumbing. Safe by design: on a rooted device
nothing in the root path is executed, because `NonRootEngine` is created lazily
and `toggleService()` dispatches on the detected mode.

Files added (`application/app/src/main/java/com/android/zdtd/service/noroot/`):

| File | Role |
|---|---|
| `RootAvailability.kt` | Probes module dir + token + su; picks ROOT vs NON_ROOT. |
| `NonRootProfiles.kt` | Reads/writes the *same* `active.json` / `setting.json` / `app/uid/user_program` layout the daemon uses, mirrored under `filesDir/working_folder`. |
| `NonRootBinaries.kt` | Installs engine binaries from APK assets to `no_backup/bin` (SHA-256 verified, atomic — same pattern as `DpiDetectorBinary`). |
| `VpnEngineService.kt` | `VpnService` that establishes the TUN, passes the fd to the engine, and dies with it. Per-app routing via `addAllowedApplication`. Substitutes `%FD%` in argv and in the mihomo YAML; starts the sing-box socks upstream and waits for its port before launching tun2socks. |
| `NonRootEngine.kt` | Resolves the first enabled profile (sing-box VPN → mihomo → tun2socks wrapper) and launches it. |
| `NonRootStatus.kt` | Synthesizes `ApiModels.StatusReport` so all existing UI works unchanged. |

Wiring touched (all additive):

- `AndroidManifest.xml`: `BIND_VPN_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `VpnEngineService` declaration (`specialUse`).
- `RootConfigManager.kt`: new `isRootAvailable()` (proves a root shell works, not just that files exist).
- `MainViewModel.kt`: `nonRootMode` / `nonRootRunning` / `nonRootConsentIntent` in `UiState`; `resolveRootMode()` in `onAppStart()`; `toggleService()` dispatches per mode; status/log polling skips the daemon in non-root mode.
- `MainActivity.kt`: VPN consent launcher (`onVpnConsentResult`).
- `ZdtdActions.kt`: `onVpnConsentResult(granted)`.
- `HomeScreen.kt`: an extra hint line in non-root mode (no layout change).
- `proguard-rules.pro`: keep the `noroot` package.
- `res/values/strings.xml`: 6 new strings.

### Phase 2 — in progress
1. **Profile-layout-compatible upstream orchestration** — implemented for sing-box and hysteria2.
   The non-root resolver now reads enabled servers from `profile/server/<name>/setting.json`
   and launches the corresponding `config.json` as a local upstream before starting
   `tun2socks`. It no longer assumes a non-root-only `setting.proxy` or a `servers[]`
   array that is absent from the root layout. The remaining engines still require
   engine-specific adapters before they can be advertised as fully compatible.

2. **Bundle the engine binaries** into `assets/noroot-binaries/<abi>/` — done
   via CI + Gradle (see "Binary pipeline" below).
3. **Verify each engine's exact fd argument** against the shipped versions —
   done; results are in "How each engine receives the TUN descriptor" above,
   and they changed the Phase 1 assumptions:
   - tun2socks takes `-device fd://<N>`, **not** `-fd <N>`;
   - sing-box has **no** `file_descriptor` config field, so its vpn mode now
     runs sing-box as a socks server with `tun2socks` on the fd (two processes,
     like `singbox.rs`);
   - mihomo takes the fd from `tun.file-descriptor` in its YAML.
3. Per-app selector wired to `NonRootProfiles.writeAppList` (the app picker UI
   already exists for the root flow).
4. Diagnostics screen showing the in-app engine logs from the profile `log/` dir.
5. `.zdtb` backup import into `filesDir/working_folder`.

### Binary pipeline (Phase 2)

No new build jobs: the root CI already compiles every engine and uploads it
(`sing-box-arm64`, `tun2socks-arm64`, `mihomo-arm64`, `byedpi-arm64`,
`dnscrypt-arm64`, `hysteria2-arm64`, `wireproxy-arm64`, `torproxy-arm64`,
`mieru-arm64`, and their `-arm32` halves). The non-root APK simply consumes
those same artifacts:

1. `build.yml` → `build_apk`: download the engine artifacts into
   `out/artifacts/<engine>/<arm64|arm32>/`.
2. `build_apk`: "Prepare non-root engine APK assets" copies each binary to
   `application/app/build/generated/zdt-assets/main/noroot-binaries/<abi>/<asset>`
   (the layout `NonRootBinaries.assetPath()` reads).
3. `build_apk`: `gradle assemble... -PzdtRequireNorootBinaries=true` makes a
   partial bundle fatal in CI (`verifyNorootBinaries`), while local builds stay
   lenient and still produce a root-only APK.
4. `build_apk`: "Collect final artifacts" asserts every
   `assets/noroot-binaries/<abi>/<asset>` entry is present in the APK, the same
   way it already checks `dpi-detector` / `nfqws_tester`.

Asset ↔ binary names (`NonRootBinaries.Engine`):

| Asset in APK | Artifact dir | Packaged file |
|---|---|---|
| `sing-box` | `sing-box` | `sing-box` |
| `tun2socks` | `tun2socks` | `tun2socks` |
| `mihomo` | `mihomo` | `mihomo` |
| `byedpi` | `byedpi` | `byedpi` |
| `dnscrypt` | `dnscrypt` | `dnscrypt` (upstream binary `dnscrypt-proxy`) |
| `hysteria2` | `hysteria2` | `hysteria2` |
| `wireproxy` | `wireproxy` | `wireproxy` |
| `torproxy` | `torproxy` | `torproxy` (upstream binary `tor`) |
| `mieru` | `mieru` | `mieru` |

### Known limitations without root (by design, not bugs)

- `nfqws` / `nfqws2` strategies are unavailable — NFQUEUE needs iptables.
- Per-UID `netd` binding, hotspot VPN tethering, `blockedquic` and the Zygisk
  hide module are root-only and are simply skipped in non-root mode.

### Runtime requirements for the VPN backend

- `VpnEngineService` must be declared with
  `android:permission="android.permission.BIND_VPN_SERVICE"` and must return
  the binder from `super.onBind()`; its manifest declaration also needs the
  `android.net.VpnService` intent action. Without these, Android cannot attach
  the established TUN interface to the service. The permission belongs on the
  service declaration, not as a runtime/requestable app permission.
- The VPN must install a real destination route (`0.0.0.0/0` for the current
  IPv4 backend). A route only to the synthetic TUN address creates the
  interface but does not carry application traffic through it. This mirrors the
  root daemon, which adds `0.0.0.0/0` to every `netd` VPN profile
  (`vpn_netd.rs::apply_one_profile`).
- The synthetic TUN address must stay outside the daemon's per-engine address
  pools, otherwise a non-root tunnel can collide with a root profile on a
  device that has both installed. The daemon hands out `/30` networks from
  `sing-box` `172.31.240.0`, `hysteria2` `172.31.232.0`, `mieru` `172.31.252.0`,
  `mihomo` `198.18.140.0` and `tun2socks` `198.18.100.0`; the non-root fallback
  is therefore `172.31.225.2`, below the lowest pool.
- The descriptor returned by `Builder.establish()` is detached exactly once.
  Calling `detachFd()` for logging and again when starting the engine invalidates
  the descriptor and makes every non-root engine fail at runtime.
- Root detection must perform the module/token check through `su` when the
  ordinary app process cannot read `/data/adb`; otherwise a healthy rooted
  installation is indistinguishable from a non-root device.

### Parity with the root daemon

The non-root resolver mirrors the daemon's per-program logic so a profile copied
between the two sides behaves the same. Each helper below is a Kotlin port of
the named daemon function:

| Concern | Daemon | Non-root resolver |
|---|---|---|
| `config.txt` argv | `common.rs::normalize_config_args` | `normalizeArgs` |
| sing-box DNS/route | `singbox.rs::normalize_singbox_common` | `normalizeSingBoxCommon` |
| sing-box t2s config | `singbox.rs::normalize_singbox_config_for_t2s` | `writeSingBoxConfig` |
| mieru config | `mieru.rs::sync_mieru_config_value` | `syncMieruConfig` |
| hysteria2 config | `hysteria2.rs::normalize_hysteria2_config_for_socks5` | `writeHysteria2Config` |
| hysteria2 log level | `hysteria2.rs::normalize_log_level` | `normalizeHysteria2LogLevel` |
| D2S listener port | `dnscrypt.rs` reuse-else-`first_free_d2s_port(11990)` | `parseActiveD2sListener` / `firstFreeD2sPort` |
| D2S `proxy` line | `dnscrypt.rs::connect_d2s_proxy_text` | `connectD2sProxy` |
| dnscrypt listen port | `dnscrypt.rs::parse_listen_port` | `parseDnscryptListenPort` |
| wireproxy BindAddress | `wireproxy.rs::parse_socks5_bind_address_str` | `parseWireproxySocksPort` |
| tor SocksPort | `tor.rs::parse_socks_port_from_str` | `parseTorSocksPort` |
| tun2socks log level | per-program field (`tun2socks_loglevel` / `tun2proxy_loglevel` / `loglevel`) | `tun2socksLogLevel` |
| tunnel route | `0.0.0.0/0` per netd profile | `Builder.addRoute("0.0.0.0", 0)` |
