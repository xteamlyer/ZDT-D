package com.android.zdtd.service.noroot

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Orchestrates the non-root engine: reads the profile layout (the same one the
 * root daemon uses), resolves which engine to run, and hands the TUN fd over to
 * [VpnEngineService].
 *
 * This class deliberately contains *no* knowledge of iptables, netd or NFQUEUE:
 * none of those are possible without root (see [docs/NON_ROOT.md]).
 */
class NonRootEngine(context: Context) {

  private val appContext = context.applicationContext
  private val profiles = NonRootProfiles(appContext)
  private val binaries = NonRootBinaries(appContext)

  /** Outcome of a start attempt, for the ViewModel to render. */
  sealed class StartResult {
    data class Started(val label: String) : StartResult()
    data class ConsentRequired(val intent: Intent) : StartResult()
    data class Failed(val reason: String) : StartResult()
    object AlreadyRunning : StartResult()
    object NothingEnabled : StartResult()
  }

  /**
   * Resolves the first enabled profile that can run without root and starts it.
   *
   * Priority order mirrors the daemon's VPN engines: sing-box and mihomo own
   * their TUN, everything else is reached through tun2socks.
   */
  @Synchronized
  fun start(): StartResult {
    if (isRunning()) return StartResult.AlreadyRunning

    val plan = runCatching { resolvePlan() }.getOrElse {
      Log.e(TAG, "cannot resolve non-root plan", it)
      return StartResult.Failed("cannot resolve non-root engine: ${it.message ?: it}")
    } ?: return StartResult.NothingEnabled

    // 1) Consent. establish() returns null without it, so we ask up-front.
    val prepare = VpnService.prepare(appContext)
    if (prepare != null) return StartResult.ConsentRequired(prepare)

    // 2) Engine binary.
    val engine = plan.engine
    val binaryFile = try {
      binaries.ensureInstalled(engine)
    } catch (e: NonRootBinaries.MissingEngineException) {
      Log.w(TAG, "binary not bundled: ${e.assetPath}")
      return StartResult.Failed("binary not bundled: ${engine.fileName}")
    } catch (e: Throwable) {
      return StartResult.Failed("cannot install ${engine.fileName}: ${e.message ?: e}")
    }

    // sing-box vpn mode runs two processes: sing-box as a socks server, then
    // tun2socks on the fd. Install the socks half too.
    val upstream = (plan.engineConfig as? EngineExtra.SingBoxSocksPort)?.let {
      try {
        val upBin = binaries.ensureInstalled(NonRootBinaries.Engine.SING_BOX)
        VpnEngineService.Companion.UpstreamEngine(
          binary = upBin.absolutePath,
          args = listOf("run", "-c", plan.fdConfigPath?.absolutePath ?: error("sing-box config missing")),
          port = it.port,
          logPath = profiles.logDir(plan.programId, plan.profile)
            .resolve("sing-box.log").absolutePath,
        )
      } catch (e: NonRootBinaries.MissingEngineException) {
        Log.w(TAG, "binary not bundled: ${e.assetPath}")
        return StartResult.Failed("binary not bundled: sing-box")
      } catch (e: Throwable) {
        return StartResult.Failed("cannot install sing-box: ${e.message ?: e}")
      }
    }
    ?: (plan.engineConfig as? EngineExtra.LocalUpstream)?.let { local ->
      try {
        val upBin = binaries.ensureInstalled(local.engine)
        VpnEngineService.Companion.UpstreamEngine(upBin.absolutePath, local.args, local.port, local.logPath)
      } catch (e: NonRootBinaries.MissingEngineException) {
        return StartResult.Failed("binary not bundled: ${local.engine.fileName}")
      } catch (e: Throwable) {
        return StartResult.Failed("cannot install ${local.engine.fileName}: ${e.message ?: e}")
      }
    }
    // 3) Write the engine config and resolve argv for the TUN fd.
    val cfgPath = writeEngineConfig(plan)
    val args = buildArgs(plan, engine, cfgPath, plan.engineConfig)
    val logPath = profiles.logDir(plan.programId, plan.profile)
      .resolve(engine.fileName + ".log").absolutePath

    // 4) Go.
    val intent = VpnEngineService.startIntent(
      context = appContext,
      label = plan.label,
      tunAddress = plan.tunAddress,
      packages = plan.packages,
      binary = binaryFile.absolutePath,
      args = args,
      logPath = logPath,
      // mihomo reads the fd from its YAML; the placeholder is resolved by the
      // service once the descriptor exists.
      configPath = plan.fdConfigPath?.takeIf { plan.engine == NonRootBinaries.Engine.MIHOMO }?.absolutePath,
      upstream = upstream,
      upstreams = plan.upstreams.map { it.copy(binary = it.binary) },
    )
    runCatching {
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        appContext.startForegroundService(intent)
      } else {
        appContext.startService(intent)
      }
    }.onFailure {
      return StartResult.Failed("cannot start tunnel service: ${it.message ?: it}")
    }

    return StartResult.Started(plan.label)
  }

  /** Stops the tunnel and the engine behind it. */
  @Synchronized
  fun stop() {
    runCatching { appContext.startService(VpnEngineService.stopIntent(appContext)) }
      .onFailure { Log.w(TAG, "stop failed: ${it.message ?: it}") }
  }

  fun isRunning(): Boolean {
    // The service is the source of truth: it tears itself down when the engine
    // exits, so "service alive" == "tunnel up".
    return VpnEngineService.isRunning()
  }

  // ----- plan resolution -----

  /** One unit of work: which program/profile/engine to launch. */
  private data class Plan(
    val programId: String,
    val profile: String,
    val engine: NonRootBinaries.Engine,
    val tunAddress: String,
    val packages: List<String>,
    val setting: JSONObject,
    val label: String,
    /**
     * Config file whose contents carry the `%FD%` placeholder, so the service
     * can substitute the real descriptor after VpnService.establish().
     * Currently only mihomo (`tun.file-descriptor`).
     */
    val fdConfigPath: File? = null,
    /**
     * `sing-box` vpn mode is a two-process flow (sing-box as a socks server +
     * tun2socks on the VpnService fd), so the plan needs the socks port the
     * socks server will listen on. `null` for the single-process engines.
     */
    val engineConfig: EngineExtra? = null,
    val upstreams: List<VpnEngineService.Companion.UpstreamEngine> = emptyList(),
  )

  /** Engine-specific data needed beyond argv. */
  private sealed class EngineExtra {
    /** sing-box vpn mode: the port its mixed inbound listens on. */
    data class SingBoxSocksPort(val port: Int) : EngineExtra()
    data class LocalUpstream(
      val engine: NonRootBinaries.Engine, val args: List<String>, val port: Int, val logPath: String,
    ) : EngineExtra()
  }

  private companion object {
    const val TAG = "ZDTD-NonRootEngine"
    // Daemon default for `t2s_port` / the socks upstream of a sing-box vpn
    // profile (see singbox.rs `default_t2s_port` region).
    const val DEFAULT_SINGBOX_SOCKS_PORT = 1080
  }

  private fun resolvePlan(): Plan? {
    // sing-box in VPN mode is the primary engine: it owns its TUN.
    resolveSingBoxVpn()?.let { return it }
    // mihomo in TUN mode.
    resolveMihomo()?.let { return it }
    resolveSingBoxT2s()?.let { return it }
    resolveHysteria2()?.let { return it }
    resolveByedpi()?.let { return it }
    resolveWireproxy()?.let { return it }
    resolveTor()?.let { return it }
    resolveMieru()?.let { return it }
    resolveDnscrypt()?.let { return it }
    // Everything else is reached through a local tun2socks endpoint.
    resolveTun2Socks()?.let { return it }
    return null
  }

  /**
   * sing-box VPN mode.
   *
   * Mirrors `singbox.rs`: sing-box's config has no tun inbound and exposes a
   * socks server instead; tun2socks bridges the VpnService descriptor to it.
   * The "vpn mode supports exactly one enabled server" rule is kept so
   * profiles stay interchangeable with the root version.
   */
  private fun resolveSingBoxVpn(): Plan? {
    val program = NonRootProfiles.Program.SINGBOX.id
    val active = profiles.readActive(program)
    for (name in active.enabled) {
      val setting = profiles.readSetting(program, name) ?: continue
      if (!setting.optString("mode", "t2s").equals("vpn", ignoreCase = true)) continue
      val packages = profiles.readAppList(program, name) ?: continue

      val server = findEnabledServer(program, name) ?: continue
      val socksPort = server.optInt("port", server.optInt("socks5_port", 0))
      val configPath = File(server.optString("config"))
      if (socksPort !in 1..65535 || !configPath.isFile) continue
      return Plan(
        programId = program, profile = name,
        engine = NonRootBinaries.Engine.TUN2SOCKS,
        tunAddress = tunAddressFor(setting, program, name), packages = packages,
        setting = setting, label = "sing-box / $name (${server.optString("name")})",
        fdConfigPath = writeSingBoxConfig(configPath, socksPort),
        engineConfig = EngineExtra.SingBoxSocksPort(socksPort),
      )
    }
    return null
  }

  private fun resolveMihomo(): Plan? {
    val program = NonRootProfiles.Program.MIHOMO.id
    val active = profiles.readActive(program)
    for (name in active.enabled) {
      val setting = profiles.readSetting(program, name) ?: continue
      val packages = profiles.readAppList(program, name) ?: continue
      val cfg = writeMihomoConfig(
        program, name,
        setting.optInt("t2s_port", DEFAULT_SINGBOX_SOCKS_PORT),
      ) ?: continue
      return Plan(
        programId = program,
        profile = name,
        engine = NonRootBinaries.Engine.MIHOMO,
        tunAddress = tunAddressFor(setting, program, name),
        packages = packages,
        setting = setting,
        label = "mihomo / $name",
        fdConfigPath = cfg,
      )
    }
    return null
  }

  /**
   * Fallback: a plain SOCKS5/HTTP endpoint wrapped in tun2socks.
   *
   * Used for byedpi, hysteria2, wireproxy, tor, mieru, myproxy, myprogram:
   * each of them is a local listener, and tun2socks bridges the TUN to it.
   */
  private fun resolveByedpi(): Plan? {
    val program = NonRootProfiles.Program.BYEDPI.id
    for (name in profiles.readActive(program).enabled) {
      val root = profiles.profileDir(program, name)
      val port = runCatching { JSONObject(root.resolve("port.json").readText()).optInt("port") }.getOrDefault(0)
      val cfg = root.resolve("config/config.txt")
      val packages = profiles.readAppList(program, name) ?: continue
      if (port !in 1..65535 || !cfg.isFile) continue
      val args = normalizeArgs(cfg.readText())
      return plainPlan(program, name, packages, setting = JSONObject(), label = "byedpi / $name",
        NonRootBinaries.Engine.BYEDPI, listOf("-i", "127.0.0.1", "-p", port.toString(), "-x", "2", "-E") + args, port)
    }
    return null
  }
  private fun resolveWireproxy(): Plan? {
    val program = NonRootProfiles.Program.WIREPROXY.id
    for (name in profiles.readActive(program).enabled) {
      val root = profiles.profileDir(program, name); val packages = profiles.readAppList(program, name) ?: continue
      val dir = root.resolve("server").listFiles { f -> f.isDirectory }?.sortedBy { it.name }.orEmpty().firstOrNull { d ->
        runCatching { JSONObject(d.resolve("setting.json").readText()).optBoolean("enabled") }.getOrDefault(false) && d.resolve("config.conf").isFile }
        ?: continue
      val cfg = dir.resolve("config.conf"); val m = Regex("(?im)^\\s*BindAddress\\s*=\\s*127\\.0\\.0\\.1:(\\d+)").find(cfg.readText()) ?: continue
      val port = m.groupValues[1].toIntOrNull() ?: continue
      return plainPlan(program, name, packages, JSONObject(), "wireproxy / $name (${dir.name})", NonRootBinaries.Engine.WIREPROXY, listOf("-c", cfg.absolutePath), port)
    }
    return null
  }
  private fun resolveTor(): Plan? {
    val program = NonRootProfiles.Program.TOR.id; val root = profiles.programDir(program)
    val enabled = runCatching { JSONObject(root.resolve("enabled.json").readText()).optBoolean("enabled") }.getOrDefault(false)
    val packages = profiles.readLegacyUidList(root.resolve("app/uid/user_program")).orEmpty()
    if (!enabled) return null
    val torrc = root.resolve("torrc"); if (!torrc.isFile) return null
    val port = Regex("(?im)^\\s*SocksPort\\s+127\\.0\\.0\\.1:(\\d+)").find(torrc.readText())?.groupValues?.get(1)?.toIntOrNull() ?: return null
    return plainPlan(program, "main", packages, JSONObject(), "tor", NonRootBinaries.Engine.TOR, listOf("-f", torrc.absolutePath), port)
  }
  private fun resolveMieru(): Plan? {
    val program = NonRootProfiles.Program.MIERU.id
    for (name in profiles.readActive(program).enabled) {
      val root = profiles.profileDir(program, name); val packages = profiles.readAppList(program, name) ?: continue
      val setting = profiles.readSetting(program, name) ?: JSONObject(); val port = setting.optInt("socks5_port", setting.optInt("socks5Port", 0)); val cfg = root.resolve("config.json")
      if (port !in 1..65535 || !cfg.isFile) continue
      val upstream = VpnEngineService.Companion.UpstreamEngine(
        binary = binaries.ensureInstalled(NonRootBinaries.Engine.MIERU).absolutePath,
        args = listOf("run"), port = port,
        logPath = profiles.logDir(program, name).resolve("mieru.log").absolutePath,
        environment = mapOf("MIERU_CONFIG_JSON_FILE" to cfg.absolutePath),
      )
      return plainPlan(program, name, packages, setting, "mieru / $name", NonRootBinaries.Engine.MIERU, emptyList(), port, upstreams = listOf(upstream))
    }
    return null
  }
  private fun resolveDnscrypt(): Plan? {
    val program = NonRootProfiles.Program.DNSCRYPT.id; val root = profiles.programDir(program)
    val enabled = runCatching { JSONObject(root.resolve("active.json").readText()).optBoolean("enabled") }.getOrDefault(false)
    val packages = profiles.readLegacyUidList(root.resolve("app/uid/user_program")).orEmpty()
    if (!enabled) return null
    val dnsCfg = root.resolve("setting/dnscrypt-proxy.toml"); val port = Regex("(?im)^\\s*listen_addresses\\s*=\\s*\\[\\s*\"127\\.0\\.0\\.1:(\\d+)").find(dnsCfg.takeIf { it.isFile }?.readText().orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: return null
    val d2sCfg = root.resolve("d2set/d2s.toml"); val d2sPort = 11990
    val ups = listOf(
      VpnEngineService.Companion.UpstreamEngine(binaries.ensureInstalled(NonRootBinaries.Engine.DNSCRYPT).absolutePath, listOf("-config", dnsCfg.absolutePath), port, root.resolve("log/dnscrypt.log").absolutePath),
      VpnEngineService.Companion.UpstreamEngine(binaries.ensureInstalled(NonRootBinaries.Engine.D2S).absolutePath, listOf("--config", d2sCfg.absolutePath, "--dnscrypt-config", dnsCfg.absolutePath, "run"), d2sPort, root.resolve("log/d2s.log").absolutePath))
    return plainPlan(program, "main", packages, JSONObject(), "dnscrypt", NonRootBinaries.Engine.TUN2SOCKS, listOf("-device", "fd://%FD%", "-proxy", "socks5://127.0.0.1:$d2sPort", "-loglevel", "info"), 0, upstreams = ups)
  }
  private fun normalizeArgs(raw: String): List<String> = raw.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
  private fun plainPlan(program: String, profile: String, packages: List<String>, setting: JSONObject, label: String, upstreamEngine: NonRootBinaries.Engine, upstreamArgs: List<String>, port: Int, shell: Boolean = false, upstreams: List<VpnEngineService.Companion.UpstreamEngine> = emptyList()): Plan {
    val args = if (shell) listOf("-c") + upstreamArgs else upstreamArgs
    val extra = if (upstreams.isNotEmpty()) null else EngineExtra.LocalUpstream(upstreamEngine, args, port, profiles.logDir(program, profile).resolve(upstreamEngine.fileName + ".log").absolutePath)
    return Plan(program, profile, NonRootBinaries.Engine.TUN2SOCKS, tunAddressFor(setting, program, profile), packages, setting, label, engineConfig = extra, upstreams = upstreams)
  }

  private fun resolveTun2Socks(): Plan? {
    // The upstream proxy is read from the profile setting's "proxy" field,
    // which is exactly what the daemon's tun2socks engine uses.
    for (program in NonRootProfiles.Program.entries) {
      if (program == NonRootProfiles.Program.SINGBOX ||
          program == NonRootProfiles.Program.MIHOMO ||
          program == NonRootProfiles.Program.DNSCRYPT ||
          program == NonRootProfiles.Program.TUN2SOCKS
      ) continue

      val active = profiles.readActive(program.id)
      for (name in active.enabled) {
        val setting = profiles.readSetting(program.id, name) ?: continue
        val packages = profiles.readAppList(program.id, name) ?: continue
        val proxy = setting.optString("proxy").ifBlank { continue }
        return Plan(
          programId = program.id,
          profile = name,
          engine = NonRootBinaries.Engine.TUN2SOCKS,
          tunAddress = tunAddressFor(setting, program.id, name),
          packages = packages,
          setting = setting,
          label = "${program.id} / $name",
        )
      }
    }
    return null
  }

  // ----- engine invocation -----

  /**
   * Writes the engine config (for sing-box / mihomo) and returns the argv that
   * must be appended after the binary path. [VpnEngineService] substitutes the
   * real fd for the `%FD%` placeholder.
   */
  private fun writeEngineConfig(plan: Plan): File? {
    return when (plan.engine) {
      // sing-box vpn mode: the mixed inbound is written during plan resolution
      // (resolveSingBoxVpn) because tun2socks, not sing-box, holds the fd.
      NonRootBinaries.Engine.MIHOMO -> plan.fdConfigPath
      else -> null
    }
  }

  private fun buildArgs(
    plan: Plan,
    engine: NonRootBinaries.Engine,
    cfgPath: File?,
    extra: EngineExtra?,
  ): List<String> {
    return when (engine) {
      NonRootBinaries.Engine.SING_BOX -> {
        // sing-box does not expose a TUN "file_descriptor" in its JSON config
        // (that field exists only on sing-tun's internal Options struct, which
        // the Android graphical client sets through libbox). So in non-root
        // mode sing-box keeps its own socks/mixed inbound and the VpnService fd
        // is driven by tun2socks, exactly like the root flow does for t2s.
        checkNotNull(cfgPath) { "sing-box config missing" }
        listOf("run", "-c", cfgPath.absolutePath)
      }
      NonRootBinaries.Engine.MIHOMO -> {
        // mihomo accepts the descriptor through the `tun.file-descriptor` field
        // of its YAML config (see config.RawTun.FileDescriptor).
        checkNotNull(cfgPath) { "mihomo config missing" }
        listOf("-f", cfgPath.absolutePath)
      }
      NonRootBinaries.Engine.TUN2SOCKS -> {
        // Root form: `-device tun://<name> ...` (the daemon owns /dev/tun).
        // Non-root form: the descriptor comes from VpnService. tun2socks parses
        // `-device fd://<n>` through its fdbased driver
        // (engine.parseDevice -> fdbased.Open -> strconv.Atoi), so the fd is
        // embedded in the device URL, not passed as a bare integer.
        buildList {
          add("-device")
          add("fd://" + VpnEngineService.Companion.EngineConfig.FD_PLACEHOLDER)
          add("-proxy")
          // sing-box vpn mode points tun2socks at the socks server it starts;
          // every other program reads its upstream from the profile setting.
          val proxy = (extra as? EngineExtra.SingBoxSocksPort)?.let {
            "socks5://127.0.0.1:${it.port}"
          } ?: plan.setting.optString("proxy")
          add(proxy)
          val logLevel = plan.setting.optString("loglevel").ifBlank { "info" }
          add("-loglevel")
          add(logLevel)
        }
      }
      else -> emptyList()
    }
  }

  // ----- config writers -----

  /**
   * sing-box vpn mode is a two-process flow: this config gives sing-box a
   * `mixed` inbound on [socksPort] and no tun inbound at all. The VpnService fd
   * goes to tun2socks, which forwards the tunnel to that socks endpoint — the
   * same construction the root daemon uses in `singbox.rs`
   * (`normalize_singbox_config_for_t2s`).
   */
  private fun writeSingBoxConfig(templatePath: File, socksPort: Int): File {
    val program = NonRootProfiles.Program.SINGBOX.id
    val profile = templatePath.parentFile?.parentFile?.parentFile?.name ?: "default"
    val root = if (templatePath.isFile) {
      runCatching { JSONObject(templatePath.readText()) }.getOrNull() ?: JSONObject()
    } else JSONObject()

    // VpnService owns the interface and app selection, so the root-style tun
    // inbound and route table are meaningless here.
    root.remove("route")
    root.remove("inbounds")

    root.put("inbounds", JSONArray().put(JSONObject()
      .put("type", "mixed")
      .put("tag", "mixed-in")
      .put("listen", "127.0.0.1")
      .put("listen_port", socksPort)))

    val out = profiles.profileDir(program, profile).resolve("noroot/config.json")
    out.parentFile?.mkdirs()
    out.writeText(root.toString())
    return out
  }

  /**
   * mihomo reads the descriptor from the `tun.file-descriptor` field of its YAML
   * config (config.RawTun.FileDescriptor), not from argv. The value is only
   * known after VpnService.establish(), so it is written as the
   * `%FD%` placeholder and substituted by VpnEngineService before launch.
   *
   * auto-route/auto-detect-interface must be off: VpnService already owns the
   * interface and the routing table, letting mihomo touch either would fight it.
   */
  private fun writeMihomoConfig(program: String, profile: String, mixedPort: Int): File {
    val templatePath = profiles.profileDir(program, profile).resolve("config.yaml")
    val text = if (templatePath.isFile) {
      // The profile config may already carry a tun block; drop it and re-add
      // our own so a root-style `device:` never survives into non-root mode.
      templatePath.readText()
        .replace(Regex("(?m)^tun:[\\s\\S]*?(?=^[^ \\t#]|\\z)"), "")
        .trimEnd() + "\n"
    } else {
      // Minimal mihomo config so the tunnel at least comes up; the real one is
      // filled in by the profile editor (phase 2).
      buildString {
        appendLine("proxies: []")
        appendLine("proxy-groups: []")
        appendLine("rules:")
        appendLine("  - MATCH,DIRECT")
      }
    }

    val sb = StringBuilder(text)
    if (!text.contains("mixed-port:")) {
      sb.insert(0, "mixed-port: $mixedPort\n")
    }
    sb.appendLine("tun:")
    sb.appendLine("  enable: true")
    sb.appendLine("  stack: mixed")
    // %FD% is replaced by VpnEngineService with the real VpnService descriptor.
    sb.appendLine("  file-descriptor: ").append(VpnEngineService.Companion.EngineConfig.FD_PLACEHOLDER)
    sb.appendLine("  auto-route: false")
    sb.appendLine("  auto-detect-interface: false")

    val out = profiles.profileDir(program, profile).resolve("noroot/config.yaml")
    out.parentFile?.mkdirs()
    out.writeText(sb.toString())
    return out
  }

  // ----- helpers -----

  private fun findEnabledServer(program: String, profile: String): JSONObject? {
    val root = profiles.profileDir(program, profile).resolve("server")
    val dirs = root.listFiles { f -> f.isDirectory }?.sortedBy { it.name }.orEmpty()
    for (dir in dirs) {
      val setting = runCatching { JSONObject(dir.resolve("setting.json").readText()) }.getOrNull() ?: continue
      if (!setting.optBoolean("enabled", false)) continue
      val config = dir.resolve("config.json")
      if (!config.isFile || config.length() == 0L) continue
      return JSONObject().put("name", dir.name)
        .put("port", setting.optInt("port", setting.optInt("socks5_port", 0)))
        .put("config", config.absolutePath)
    }
    return null
  }

  private fun resolveSingBoxT2s(): Plan? {
    val program = NonRootProfiles.Program.SINGBOX.id
    for (name in profiles.readActive(program).enabled) {
      val setting = profiles.readSetting(program, name) ?: continue
      if (setting.optString("mode", "t2s").equals("vpn", true)) continue
      val packages = profiles.readAppList(program, name) ?: continue
      val server = findEnabledServer(program, name) ?: continue
      val port = server.optInt("port", 0)
      val config = File(server.optString("config"))
      if (port !in 1..65535 || !config.isFile) continue
      return Plan(program, name, NonRootBinaries.Engine.TUN2SOCKS,
        tunAddressFor(setting, program, name), packages, setting,
        "sing-box / $name (${server.optString("name")})",
        engineConfig = EngineExtra.LocalUpstream(
          NonRootBinaries.Engine.SING_BOX, listOf("run", "-c", config.absolutePath),
          port, profiles.logDir(program, name).resolve("sing-box.log").absolutePath))
    }
    return null
  }

  private fun resolveHysteria2(): Plan? {
    val program = NonRootProfiles.Program.HYSTERIA2.id
    for (name in profiles.readActive(program).enabled) {
      val setting = profiles.readSetting(program, name) ?: continue
      if (setting.optString("mode", "t2s").equals("vpn", true)) continue
      val packages = profiles.readAppList(program, name) ?: continue
      val server = findEnabledServer(program, name) ?: continue
      val port = server.optInt("port", server.optInt("socks5_port", 0))
      val config = File(server.optString("config"))
      if (port !in 1..65535 || !config.isFile) continue
      return Plan(program, name, NonRootBinaries.Engine.TUN2SOCKS,
        tunAddressFor(setting, program, name), packages, setting,
        "hysteria2 / $name (${server.optString("name")})",
        engineConfig = EngineExtra.LocalUpstream(
          NonRootBinaries.Engine.HYSTERIA2,
          listOf("--disable-update-check", "-f", "console", "-l", "info", "-c", config.absolutePath, "client"),
          port, profiles.logDir(program, name).resolve("hysteria2.log").absolutePath))
    }
    return null
  }

  private fun firstEnabledServer(servers: JSONArray?): String? {
    if (servers == null) return null
    for (i in 0 until servers.length()) {
      val obj = servers.optJSONObject(i) ?: continue
      if (obj.optBoolean("enabled", false)) {
        return obj.optString("name").ifBlank { obj.optString("tag") }
      }
    }
    return null
  }

  /**
   * TUN address for the profile. Reuses the daemon's scheme when the setting
   * already carries one (`tun_address`), otherwise falls back to the stable
   * sing-box base so the two versions never overlap.
   */
  private fun tunAddressFor(setting: JSONObject, program: String, profile: String): String {
    setting.optString("tun_address").takeIf { it.isNotBlank() }?.let { return it }
    // 172.31.240.0/20 is the daemon's SINGBOX_NET_BASE; keep out of its range.
    return "172.31.242.2"
  }
}
