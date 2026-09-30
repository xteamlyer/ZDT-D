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
   * Priority order mirrors the daemon's VPN engines: sing-box and mihomo own
   * their TUN, everything else is reached through tun2socks.
   */
  @Synchronized
  fun start(): StartResult {
    if (isRunning()) return StartResult.AlreadyRunning

    val plan = runCatching { resolvePlan() }.getOrElse {
      Log.e(TAG, "cannot resolve non-root plan", it)
      val reason = (it as? MissingEngineAtPlanException)?.let { e -> "binary not bundled: ${e.engine.fileName}" }
        ?: "cannot resolve non-root engine: ${it.message ?: it}"
      return StartResult.Failed(reason)
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

    // sing-box vpn mode runs two processes: sing-box as a socks server, then tun2socks on the
    // fd. Install the socks half too; the other two-process plans (sing-box t2s, hysteria2,
    // mieru, dnscrypt) already installed theirs while resolving `plan.upstreams`.
    val upstream = (plan.engineConfig as? EngineExtra.SingBoxSocksPort)?.let {
      try {
        val upBin = binaries.ensureInstalled(NonRootBinaries.Engine.SING_BOX)
        VpnEngineService.Companion.UpstreamEngine(
          binary = upBin.absolutePath,
          args = listOf("run", "-c", plan.fdConfigPath?.absolutePath ?: error("sing-box config missing")),
          port = it.port,
          logPath = profiles.logDir(plan.programId, plan.profile).resolve("sing-box.log").absolutePath,
        )
      } catch (e: NonRootBinaries.MissingEngineException) {
        Log.w(TAG, "binary not bundled: ${e.assetPath}")
        return StartResult.Failed("binary not bundled: sing-box")
      } catch (e: Throwable) {
        return StartResult.Failed("cannot install sing-box: ${e.message ?: e}")
      }
    }
    // 3) Resolve argv for the TUN fd (configs were written during plan resolution).
    val args = buildArgs(plan, engine, plan.fdConfigPath, plan.engineConfig)
    val logPath = profiles.logDir(plan.programId, plan.profile).resolve(engine.fileName + ".log").absolutePath

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
    /**
     * Two-process plan where a local daemon (sing-box, hysteria2, mieru,
     * dnscrypt+d2s, byedpi, wireproxy, tor) listens on a SOCKS port and
     * tun2socks bridges the VpnService fd to it. [proxy] is the URL tun2socks
     * dials, which is *not* in the profile setting for these programs, so it
     * has to be carried here. The daemons are listed in [Plan.upstreams].
     */
    data class Tun2SocksUpstream(val proxy: String) : EngineExtra()
  }

  private companion object {
    const val TAG = "ZDTD-NonRootEngine"
    // Daemon defaults: `t2s_port` / the socks upstream of a sing-box vpn profile
    // (singbox.rs `default_t2s_port`), the first loopback port tried for the D2S
    // listener (dnscrypt.rs `D2S_AUTO_PORT_START`), mieru's default `rpc_port`,
    // and the fallback TUN address when the setting has no `tun_address`
    // (outside every daemon engine pool — see [tunAddressFor]).
    const val DEFAULT_SINGBOX_SOCKS_PORT = 1080
    const val DEFAULT_TUN_ADDRESS = "172.31.225.2"
    const val D2S_AUTO_PORT_START = 11990
    const val DEFAULT_MIERU_RPC_PORT = 8964
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
        fdConfigPath = writeSingBoxConfig(configPath, socksPort, setting),
        engineConfig = EngineExtra.SingBoxSocksPort(socksPort),
      )
    }
    return null
  }

  private fun resolveMihomo(): Plan? {
    val program = NonRootProfiles.Program.MIHOMO.id
    for (name in profiles.readActive(program).enabled) {
      val setting = profiles.readSetting(program, name) ?: continue
      val packages = profiles.readAppList(program, name) ?: continue
      return Plan(
        programId = program, profile = name, engine = NonRootBinaries.Engine.MIHOMO,
        tunAddress = tunAddressFor(setting, program, name), packages = packages, setting = setting,
        label = "mihomo / $name",
        fdConfigPath = writeMihomoConfig(program, name, setting.optInt("t2s_port", DEFAULT_SINGBOX_SOCKS_PORT)),
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
      // byedpi is a plain listener: the daemon spawns it with exactly these flags
      // (`-i 127.0.0.1 -p <port> -x 2 -E <config args>`) and reaches it through a SOCKS
      // bridge, so it runs as an upstream here.
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
      val cfg = dir.resolve("config.conf")
      // Same parse as the daemon (`parse_socks5_bind_address`): only valid
      // inside the [Socks5] section, and the host must be 127.0.0.1.
      val port = parseWireproxySocksPort(cfg.readText()) ?: continue
      return plainPlan(program, name, packages, JSONObject(), "wireproxy / $name (${dir.name})", NonRootBinaries.Engine.WIREPROXY, listOf("-c", cfg.absolutePath), port)
    }
    return null
  }

  /** Kotlin port of wireproxy.rs `parse_socks5_bind_address_str`. */
  private fun parseWireproxySocksPort(raw: String): Int? {
    var inSocks5 = false
    for (rawLine in raw.lineSequence()) {
      val line = rawLine.trim()
      if (line.isEmpty() || line.startsWith('#') || line.startsWith(';')) continue
      if (line.startsWith('[') && line.endsWith(']')) {
        inSocks5 = line.trimStart('[').trimEnd(']').trim().equals("Socks5", ignoreCase = true)
        continue
      }
      if (!inSocks5) continue
      val (key, value) = line.split('=', limit = 2).takeIf { it.size == 2 } ?: continue
      if (!key.trim().equals("BindAddress", ignoreCase = true)) continue
      // rsplit_once(':'): the last colon separates host and port, so an IPv6
      // host parses as an invalid port and is rejected, as in the daemon.
      val host = value.trim().substringBeforeLast(':').trim()
      val port = value.trim().substringAfterLast(':').trim().toIntOrNull() ?: return null
      if (host != "127.0.0.1" || port <= 0) return null
      return port
    }
  }
  private fun resolveTor(): Plan? {
    val program = NonRootProfiles.Program.TOR.id; val root = profiles.programDir(program)
    val enabled = runCatching { JSONObject(root.resolve("enabled.json").readText()).optBoolean("enabled") }.getOrDefault(false)
    val packages = profiles.readLegacyUidList(root.resolve("app/uid/user_program")).orEmpty()
    if (!enabled) return null
    // Same parse as the daemon (`parse_socks_port_from_str`): the host must be
    // 127.0.0.1, not just anything before a colon.
    val port = parseTorSocksPort(torrc.readText()) ?: return null
    return plainPlan(program, "main", packages, JSONObject(), "tor", NonRootBinaries.Engine.TOR, listOf("-f", torrc.absolutePath), port)
  }

  /** Kotlin port of tor.rs `parse_socks_port_from_str`. */
  private fun parseTorSocksPort(raw: String): Int? {
    for (rawLine in raw.lineSequence()) {
      val line = rawLine.trim()
      if (line.isEmpty() || line.startsWith('#') || line.startsWith(';')) continue
      if (!line.lowercase().startsWith("socksport")) continue
      val parts = line.split(Regex("\\s+"))
      if (!parts.firstOrNull().equals("SocksPort", ignoreCase = true)) continue
      val addr = parts.getOrNull(1)?.trim() ?: return null
      val host = addr.substringBeforeLast(':').trim()
      val port = addr.substringAfterLast(':').trim().toIntOrNull() ?: return null
      if (host != "127.0.0.1" || port <= 0) return null
      return port
    }
    return null
  }

  private fun resolveMieru(): Plan? {
    val program = NonRootProfiles.Program.MIERU.id
    for (name in profiles.readActive(program).enabled) {
      val root = profiles.profileDir(program, name); val packages = profiles.readAppList(program, name) ?: continue
      val setting = profiles.readSetting(program, name) ?: JSONObject(); val port = setting.optInt("socks5_port", setting.optInt("socks5Port", 0)); val cfg = root.resolve("config.json")
      if (port !in 1..65535 || !cfg.isFile) continue
      // The daemon rewrites the config into a *runtime* copy before spawning (mieru.rs
      // `config.runtime.json`) so mieru always starts with the profile's own socks5/rpc ports
      // bound to loopback and the user's config.json stays untouched; without this mieru falls
      // back to its built-in defaults and the port above never listens.
      val runtimeCfg = syncMieruConfig(cfg, name, setting, port)
      val upstream = VpnEngineService.Companion.UpstreamEngine(
        binary = binaries.ensureInstalled(NonRootBinaries.Engine.MIERU).absolutePath,
        args = listOf("run"), port = port,
        logPath = profiles.logDir(program, name).resolve("mieru.log").absolutePath,
        environment = mapOf("MIERU_CONFIG_JSON_FILE" to runtimeCfg.absolutePath),
      )
      return plainPlan(program, name, packages, setting, "mieru / $name", NonRootBinaries.Engine.MIERU, emptyList(), port, upstreams = listOf(upstream))
    }
    return null
  }

  /**
   * Mirrors mieru.rs `sync_mieru_config_value`: pins `activeProfile`, `rpcPort`,
   * `socks5Port`, `loggingLevel` (uppercase) and `socks5ListenLAN=false`, and drops a
   * stale `httpProxyPort` so the profile's HTTP proxy cannot steal traffic from the
   * tunnel. Returns the runtime copy (`config.runtime.json`), leaving the user's
   * `config.json` untouched.
   */
  private fun syncMieruConfig(cfg: File, profile: String, setting: JSONObject, socks5Port: Int): File {
    val obj = runCatching { JSONObject(cfg.readText()) }.getOrNull() ?: JSONObject()
    val activeProfile = firstMieruProfileName(obj.optJSONArray("profiles")) ?: profile
    obj.put("activeProfile", activeProfile)
    obj.put("rpcPort", setting.optInt("rpc_port", setting.optInt("rpcPort", DEFAULT_MIERU_RPC_PORT)))
    obj.put("socks5Port", socks5Port)
    obj.put("loggingLevel", setting.optString("mieru_loglevel", setting.optString("loggingLevel", "info")).uppercase())
    obj.put("socks5ListenLAN", false)
    obj.remove("httpProxyPort")
    obj.remove("httpProxyListenLAN")
    if (!obj.has("profiles")) obj.put("profiles", defaultMieruProfiles(activeProfile))
    val out = cfg.parentFile?.resolve("config.runtime.json") ?: cfg
    out.parentFile?.mkdirs()
    out.writeText(obj.toString())
    return out
  }
  private fun firstMieruProfileName(profiles: JSONArray?): String? {
    if (profiles == null) return null
    for (i in 0 until profiles.length()) {
      val name = profiles.optJSONObject(i)?.optString("profileName")?.trim().orEmpty()
      if (name.isNotEmpty()) return name
    }
    return null
  }

  private fun defaultMieruProfiles(name: String): JSONArray = JSONArray().put(JSONObject().put("profileName", name))

  private fun resolveDnscrypt(): Plan? {
    val program = NonRootProfiles.Program.DNSCRYPT.id; val root = profiles.programDir(program)
    val enabled = runCatching { JSONObject(root.resolve("active.json").readText()).optBoolean("enabled") }.getOrDefault(false)
    val packages = profiles.readLegacyUidList(root.resolve("app/uid/user_program")).orEmpty()
    if (!enabled) return null
    val dnsCfg = root.resolve("setting/dnscrypt-proxy.toml"); val port = parseDnscryptListenPort(dnsCfg) ?: return null
    val d2sCfg = root.resolve("d2set/d2s.toml")
    // The daemon creates a minimal d2s.toml when the profile has none (`ensure_d2s_config_exists`);
    // d2s refuses to run without it.
    ensureD2sConfig(d2sCfg)
    // It then reuses an existing local D2S listener when the TOML already points at one
    // (`parse_active_d2s_listener`), otherwise it picks the first free loopback port from
    // 11990 and *writes* `proxy = 'socks5://127.0.0.1:PORT'` into the TOML (`connect_d2s_proxy`).
    // Hardcoding 11990 would collide with the root daemon's own D2S, and skipping the write
    // would leave dnscrypt-proxy with no upstream at all.
    val d2sPort = parseActiveD2sListener(dnsCfg)?.port ?: firstFreeD2sPort(D2S_AUTO_PORT_START)
    val runtimeDnsCfg = connectD2sProxy(dnsCfg, d2sPort)
    val ups = listOf(
      VpnEngineService.Companion.UpstreamEngine(binaries.ensureInstalled(NonRootBinaries.Engine.DNSCRYPT).absolutePath, listOf("-config", runtimeDnsCfg.absolutePath), port, root.resolve("log/dnscrypt.log").absolutePath),
      VpnEngineService.Companion.UpstreamEngine(binaries.ensureInstalled(NonRootBinaries.Engine.D2S).absolutePath, listOf("--config", d2sCfg.absolutePath, "--dnscrypt-config", runtimeDnsCfg.absolutePath, "run"), d2sPort, root.resolve("log/d2s.log").absolutePath))
    // `port` here is the dnscrypt listen port, not a SOCKS port; tun2socks must dial the D2S
    // listener, so the proxy URL is built from d2sPort.
    return plainPlan(program, "main", packages, JSONObject(), "dnscrypt", NonRootBinaries.Engine.TUN2SOCKS, emptyList(), d2sPort, upstreams = ups)
  }

  /**
   * Kotlin counterpart of dnscrypt.rs `connect_d2s_proxy` + `connect_d2s_proxy_text`:
   * writes `proxy = 'socks5://127.0.0.1:[port]'` into a runtime copy of the
   * user's TOML without reserializing it, preserving the previous proxy line as
   * a comment. dnscrypt-proxy only dials a proxy when the key is present, so
   * without this step the tunnel has no upstream.
   */
  private fun connectD2sProxy(toml: File, port: Int): File {
    val raw = if (toml.isFile) runCatching { toml.readText() }.getOrDefault("") else ""
    val newline = if (raw.contains("\r\n")) "\r\n" else "\n"
    val hadTrailingNewline = raw.endsWith('\n') || raw.endsWith('\r')
    val lines = raw.split("\r\n|\n|\r".toRegex()).dropLastWhile { it.isEmpty() }.toMutableList()
    val newProxy = "proxy = 'socks5://127.0.0.1:$port'"
    var firstSection: Int? = null
    var activeProxy: Int? = null
    var localCommentedProxy: Int? = null
    for ((index, line) in lines.withIndex()) {
      val trimmed = line.trimStart()
      if (trimmed.startsWith('[')) { firstSection = index; break }
      if (isProxyAssignment(trimmed)) { activeProxy = index; break }
      if (localCommentedProxy == null && isCommentedLocalProxyAssignment(trimmed)) localCommentedProxy = index
    }

    // The daemon rewrites the matched line in place, keeping its indentation.
    fun indentOf(idx: Int) = lines[idx].let { it.substring(0, it.length - it.trimStart().length) }
    when {
      activeProxy != null -> {
        val idx = activeProxy!!
        val indent = indentOf(idx)
        lines[idx] = "${indent}# ZDT-D previous proxy: ${lines[idx].trimStart()}"
        lines.add(idx + 1, "$indent$newProxy")
      }
      localCommentedProxy != null -> lines[localCommentedProxy!!] = "${indentOf(localCommentedProxy!!)}$newProxy"
      else -> lines.add(firstSection ?: lines.size, newProxy)
    }

    val out = profiles.profileDir(NonRootProfiles.Program.DNSCRYPT.id, "main").resolve("noroot/dnscrypt-proxy.toml")
    out.parentFile?.mkdirs()
    out.writeText(buildString {
      append(lines.joinToString(newline))
      if (hadTrailingNewline || lines.isEmpty()) append(newline)
    })
    return out
  }

  /** Same minimal config the daemon writes for a missing d2s.toml (dnscrypt.rs `D2S_MINIMAL_CONFIG`). */
  private fun ensureD2sConfig(cfg: File) {
    if (cfg.isFile) return
    cfg.parentFile?.mkdirs()
    runCatching { cfg.writeText("backends = []\ndirect_fallback = true\n") }
  }

  private fun isProxyAssignment(line: String): Boolean {
    if (line.startsWith('#')) return false
    val eq = line.indexOf('=')
    return eq > 0 && line.substring(0, eq).trim() == "proxy"
  }

  private fun isCommentedLocalProxyAssignment(line: String): Boolean {
    val commented = line.removePrefix("#").trimStart()
    return isProxyAssignment(commented) && listOf("socks5://127.0.0.1:", "socks5://localhost:", "socks5://[::1]:").any { commented.contains(it) }
  }

  /**
   * Parses the dnscrypt listen port the same way the daemon does
   * (dnscrypt.rs `parse_listen_port`): accepts `127.0.0.1:PORT`, `[::1]:PORT`
   * and `::1:PORT`, tolerating a single or a pair of addresses.
   */
  private fun parseDnscryptListenPort(toml: File): Int? {
    if (!toml.isFile) return null
    val text = runCatching { toml.readText() }.getOrNull() ?: return null
    text.lineSequence().forEach { rawLine ->
      val line = rawLine.trim()
      if (!line.startsWith("listen_addresses")) return@forEach
      val ports = mutableListOf<Int>()
      for (pat in listOf("127.0.0.1:", "[::1]:", "::1:")) {
        var start = 0
        while (true) {
          val rel = line.indexOf(pat, start)
          if (rel < 0) break
          val pos = rel + pat.length
          line.substring(pos).takeWhile { it.isDigit() }.toIntOrNull()?.let { ports.add(it) }
          start = pos
        }
      }
      if (ports.isEmpty()) return null
      return ports.first()
    }
    return null
  }

  /** Reads the `proxy = 'socks5://127.0.0.1:PORT'` line the daemon looks for (dnscrypt.rs `parse_active_d2s_listener`). */
  private fun parseActiveD2sListener(toml: File): java.net.InetSocketAddress? {
    if (!toml.isFile) return null
    val text = runCatching { toml.readText() }.getOrNull() ?: return null
    // The daemon parses the TOML and reads the `proxy` key; the non-root side
    // only needs that one line, so a lightweight scan is enough.
    val proxy = text.lineSequence()
      .map { it.trim() }
      .firstOrNull { it.startsWith("proxy") && it.contains('=') }
      ?.substringAfter('=')?.trim()?.trim('\'', '"')?.takeIf { it.isNotBlank() }
      ?: return null
    val endpoint = proxy.removePrefix("socks5://").takeIf { it.isNotEmpty() } ?: return null
    // The daemon only accepts an unauthenticated loopback host:port;
    // `localhost:PORT` is accepted literally, everything else must be a
    // host:port pair whose host is loopback.
    if (endpoint.any { it == '@' || it == '/' || it == '?' || it == '#' }) return null
    val (host, portStr) = if (endpoint.startsWith("localhost:")) {
      "127.0.0.1" to endpoint.removePrefix("localhost:")
    } else {
      endpoint.substringBeforeLast(':') to endpoint.substringAfterLast(':')
    }
    val port = portStr.trim().toIntOrNull() ?: return null
    if (port <= 0) return null
    val loopback = host == "127.0.0.1" || host == "[::1]" || host == "::1"
    if (!loopback) return null
    return java.net.InetSocketAddress.createUnresolved(host.removeSurrounding("[", "]"), port)
  }

  /** First free IPv4 loopback TCP port from [start], mirroring the daemon's `first_free_d2s_port`. */
  private fun firstFreeD2sPort(start: Int): Int {
    var port = start
    while (port in 1..65535) {
      try {
        java.net.ServerSocket(port, 1, java.net.InetAddress.getByName("127.0.0.1")).use { return port }
      } catch (_: java.net.BindException) {
        port++
      } catch (_: Throwable) {
        return start
      }
    }
    return start
  }

  /**
   * Tokenizes a multiline config file into argv without invoking a shell.
   *
   * Faithful Kotlin port of the daemon's `programs/common.rs`
   * `normalize_config_args`, so a `config.txt` that works under root produces
   * byte-for-byte identical argv in non-root mode: unquoted whitespace separates
   * arguments; single/double quotes group whitespace and are removed; adjacent
   * quoted/unquoted fragments form one argument (`-H:"1.com 2.com"` ->
   * `-H:1.com 2.com`); backslash escapes whitespace, quotes, or another
   * backslash; backslash + LF/CRLF is a line continuation. No shell expansion
   * or execution is performed.
   */
  private fun normalizeArgs(raw: String): List<String> {
    // Remove explicit line continuations and turn other line breaks into spaces.
    val it = raw.iterator()
    val sb = StringBuilder(raw.length)    while (it.hasNext()) {
      val c = it.nextChar()
      if (c == '\\') {
        if (it.hasNext()) {
          val next = it.nextChar()
          if (next == '\n') continue
          if (next == '\r') {
            if (it.hasNext() && it.nextChar() == '\n') continue
            continue // a lone trailing '\r' is a continuation too
          }
          sb.append(c).append(next)
          continue
        }
        sb.append(c)
        continue
      }
      if (c == '\n' || c == '\r') sb.append(' ') else sb.append(c)
    }
    val s = sb.toString()
    val out = mutableListOf<String>()
    val token = StringBuilder()
    var tokenStarted = false
    var inSingle = false
    var inDouble = false
    fun flushToken() {
      if (tokenStarted) {
        if (token.toString() != "\\") out.add(token.toString())
        token.clear()
        tokenStarted = false
      }
    }

    var i = 0
    while (i < s.length) {
      val c = s[i]
      when {
        inSingle -> {
          if (c == '\'') inSingle = false else token.append(c)
        }
        inDouble -> when (c) {
          '"' -> inDouble = false
          '\\' -> {
            val next = s.elementAtOrNull(i + 1)
            if (next != null && (next == '"' || next == '\\' || next.isWhitespace())) {
              token.append(next); i++
            } else {
              token.append(c)
            }
          }
          else -> token.append(c)
        }
        c.isWhitespace() -> flushToken()
        c == '\'' -> { inSingle = true; tokenStarted = true }
        c == '"' -> { inDouble = true; tokenStarted = true }
        c == '\\' -> {
          tokenStarted = true
          val next = s.elementAtOrNull(i + 1)
          when {
            // A standalone `\` between arguments is kept rather than turning
            // into an argument containing one escaped space.
            next != null && next.isWhitespace() && token.isEmpty() -> token.append('\\')
            next != null && (next.isWhitespace() || next == '\'' || next == '"' || next == '\\') -> {
              token.append(next); i++
            }
            else -> token.append('\\')
          }
        }
        else -> { token.append(c); tokenStarted = true }
      }
      i++
    }
    flushToken()
    return out
  }

  /**
   * Builds a plan where tun2socks holds the VpnService fd and forwards it to a local listener
   * ([upstreamEngine] on [port]), or — when [upstreams] is given — to one of the daemons started
   * by the caller. The proxy URL tun2socks dials is *not* read from the profile setting for these
   * programs (only the standalone `tun2socks` engine stores `proxy` there), so it is carried in
   * [EngineExtra.Tun2SocksUpstream].
   */
  private fun plainPlan(program: String, profile: String, packages: List<String>, setting: JSONObject, label: String, upstreamEngine: NonRootBinaries.Engine, upstreamArgs: List<String>, port: Int, shell: Boolean = false, upstreams: List<VpnEngineService.Companion.UpstreamEngine> = emptyList()): Plan {
    val args = if (shell) listOf("-c") + upstreamArgs else upstreamArgs
    val logPath = profiles.logDir(program, profile).resolve(upstreamEngine.fileName + ".log").absolutePath
    val engineUpstreams = upstreams.ifEmpty {
      listOf(VpnEngineService.Companion.UpstreamEngine(ensureUpstreamBinary(upstreamEngine, label), args, port, logPath))
    }
    return Plan(
      program, profile, NonRootBinaries.Engine.TUN2SOCKS,
      tunAddressFor(setting, program, profile), packages, setting, label,
      engineConfig = EngineExtra.Tun2SocksUpstream("socks5://127.0.0.1:$port"),
      upstreams = engineUpstreams,
    )
  }

  /** Installs an upstream engine binary, mapping a missing asset to a start failure. */
  private fun ensureUpstreamBinary(engine: NonRootBinaries.Engine, label: String): String = try {
    binaries.ensureInstalled(engine).absolutePath
  } catch (e: NonRootBinaries.MissingEngineException) {
    Log.w(TAG, "binary not bundled: ${e.assetPath}")
    throw MissingEngineAtPlanException(label, engine)
  } catch (e: Throwable) {
    throw MissingEngineAtPlanException(label, engine, e)
  }

  /** Surfaced from plan resolution so [start] can report it as a user-visible failure. */
  private class MissingEngineAtPlanException(val label: String, val engine: NonRootBinaries.Engine, cause: Throwable? = null) :
    Exception("binary not bundled: ${engine.fileName} for $label", cause)

  private fun resolveTun2Socks(): Plan? {
    // The upstream proxy is read from the profile setting's "proxy" field,
    // which is exactly what the daemon's tun2socks engine uses.
    val skipped = setOf(NonRootProfiles.Program.SINGBOX, NonRootProfiles.Program.MIHOMO, NonRootProfiles.Program.DNSCRYPT, NonRootProfiles.Program.TUN2SOCKS)
    for (program in NonRootProfiles.Program.entries) {
      if (program in skipped) continue

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

  private fun buildArgs(plan: Plan, engine: NonRootBinaries.Engine, cfgPath: File?, extra: EngineExtra?): List<String> = when (engine) {
    NonRootBinaries.Engine.SING_BOX -> {
      // sing-box has no JSON `file_descriptor` field (that exists only on sing-tun's internal
      // Options struct, which the Android client sets via libbox), so it keeps a socks/mixed
      // inbound and tun2socks drives the fd, exactly like the root t2s flow.
      checkNotNull(cfgPath) { "sing-box config missing" }
      listOf("run", "-c", cfgPath.absolutePath)
    }
    NonRootBinaries.Engine.MIHOMO -> {
      // mihomo takes the descriptor from the `tun.file-descriptor` YAML field.
      checkNotNull(cfgPath) { "mihomo config missing" }
      listOf("-f", cfgPath.absolutePath)
    }
    NonRootBinaries.Engine.TUN2SOCKS -> {
      // Root form is `-device tun://<name>` (the daemon owns /dev/tun); the descriptor here comes
      // from VpnService, so it is embedded in the device URL as `fd://<n>` (tun2socks fdbased driver).
      buildList {
        add("-device")
        add("fd://" + VpnEngineService.Companion.EngineConfig.FD_PLACEHOLDER)
        add("-proxy")
        // sing-box vpn mode points tun2socks at the socks server it starts; mieru / dnscrypt point
        // it at the daemon started as an upstream; every other program reads its upstream from the
        // profile setting.
        val proxy = when (extra) {
          is EngineExtra.SingBoxSocksPort -> "socks5://127.0.0.1:${extra.port}"
          is EngineExtra.Tun2SocksUpstream -> extra.proxy
          else -> plan.setting.optString("proxy")
        }
        add(proxy)
        add("-loglevel")
        add(tun2socksLogLevel(plan.setting))
      }
    }
    else -> emptyList()
  }

  // ----- config writers -----

  /**
   * sing-box vpn mode is a two-process flow: this config gives sing-box a `mixed` inbound on
   * [socksPort] and no tun inbound at all. The VpnService fd goes to tun2socks, which forwards the
   * tunnel to that socks endpoint — the same construction the root daemon uses in `singbox.rs`
   * (`normalize_singbox_config_for_t2s`). The DNS and route blocks are rebuilt by
   * `normalizeSingBoxCommon` too: without them sing-box resolves through the system DNS, which is
   * exactly the traffic a DPI-bypass tunnel must not leak.
   */
  private fun writeSingBoxConfig(templatePath: File, socksPort: Int, setting: JSONObject): File {
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

    normalizeSingBoxCommon(root, setting)

    val out = profiles.profileDir(program, profile).resolve("noroot/config.json")
    out.parentFile?.mkdirs()
    out.writeText(root.toString())
    return out
  }

  /**
   * Kotlin counterpart of `singbox.rs::normalize_singbox_common`: installs the
   * ZDT-D DNS server chain (local/direct/remote/fakeip) and the route rules
   * (sniff, hijack-dns, reject multicast, final→proxy) that make the tunnel
   * self-contained instead of leaking DNS to the system resolver.
   */
  private fun normalizeSingBoxCommon(root: JSONObject, setting: JSONObject) {
    val dnsServers = setting.optJSONArray("dns")?.let { arr ->
      (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } }
    } ?: listOf("8.8.8.8")
    val primaryDns = dnsServers.firstOrNull { isIpv4(it) } ?: "8.8.8.8"

    // Preserve domains already routed to dns-direct by the user's config, then
    // add the DoH endpoint itself plus every outbound server hostname, so none
    // of them is resolved through the fakeip range.
    val directDomains = collectSingBoxDirectDnsDomains(root)
    directDomains.add("dns.google")
    collectSingBoxOutboundDomains(root).forEach { directDomains.add(it) }
    val inboundTags = collectSingBoxInboundTags(root)

    val dnsRules = JSONArray()
    if (directDomains.isNotEmpty()) {
      dnsRules.put(JSONObject().put("domain", JSONArray(directDomains.toList())).put("server", "dns-direct"))
    }
    if (inboundTags.isNotEmpty()) {
      dnsRules.put(JSONObject()
        .put("inbound", JSONArray(inboundTags))
        .put("query_type", JSONArray(listOf("A", "AAAA")))
        .put("server", "dns-fake")
        .put("disable_cache", true))
    }

    root.put("dns", JSONObject()
      .put("servers", JSONArray().apply {
        put(JSONObject().put("type", "local").put("tag", "dns-local"))
        put(JSONObject().put("type", "udp").put("tag", "dns-direct").put("server", primaryDns).put("server_port", 53))
        put(JSONObject().put("type", "https").put("tag", "dns-remote").put("server", "dns.google").put("server_port", 443)
          .put("path", "/dns-query")
          .put("domain_resolver", JSONObject().put("server", "dns-direct").put("strategy", "ipv4_only")))
        put(JSONObject().put("type", "fakeip").put("tag", "dns-fake")
          .put("inet4_range", "198.18.0.0/15").put("inet6_range", "fc00::/18"))
      })
      .put("rules", dnsRules)
      .put("final", "dns-remote")
      .put("independent_cache", true)
      .put("strategy", "ipv4_only"))

    // The daemon keeps the user's route object and only overwrites the keys it
    // owns, so a config that ships its own rule_set or final still works.
    val route = root.optJSONObject("route") ?: JSONObject()
    root.remove("route")
    route.put("auto_detect_interface", true)
    route.put("default_domain_resolver", JSONObject().put("server", "dns-direct").put("strategy", "ipv4_only"))
    val routeRules = JSONArray()
    inboundTags.forEach { tag -> routeRules.put(JSONObject().put("inbound", JSONArray(listOf(tag))).put("action", "sniff")) }
    routeRules.put(JSONObject().put("action", "hijack-dns").put("port", JSONArray(listOf(53))))
    routeRules.put(JSONObject().put("action", "hijack-dns").put("protocol", JSONArray(listOf("dns"))))
    routeRules.put(JSONObject().put("action", "reject")
      .put("ip_cidr", JSONArray(listOf("224.0.0.0/3", "ff00::/8")))
      .put("source_ip_cidr", JSONArray(listOf("224.0.0.0/3", "ff00::/8"))))
    route.put("rules", routeRules)
    // `or_insert_with`: keep a user-provided rule_set, default to empty.
    if (!route.has("rule_set")) route.put("rule_set", JSONArray())
    if (!route.has("final") && hasSingBoxOutboundTag(root, "proxy")) {
      route.put("final", "proxy")
    }
    root.put("route", route)
  }

  private fun collectSingBoxInboundTags(root: JSONObject): List<String> {
    val tags = root.optJSONArray("inbounds")?.let { arr ->
      (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("tag")?.ifBlank { null } }
    } ?: emptyList()
    return tags.distinct().sorted()
  }

  private fun collectSingBoxOutboundDomains(root: JSONObject): Set<String> {
    val out = sortedSetOf<String>()
    val outbounds = root.optJSONArray("outbounds") ?: return out
    // The daemon collects every server hostname that is neither an IPv4 nor an
    // IPv6 literal (which contains ':'), so domains behind a proxy get
    // dns-direct instead of fakeip.
    for (i in 0 until outbounds.length()) {
      val server = outbounds.optJSONObject(i)?.optString("server")?.trim()?.ifBlank { null } ?: continue
      if (!isIpv4(server) && !server.contains(':')) out.add(server)
    }
    return out
  }

  /**
   * Domains the user's own DNS rules already send to `dns-direct`; keeping them
   * avoids re-resolving them through fakeip (`collect_singbox_direct_dns_domains`).
   */
  private fun collectSingBoxDirectDnsDomains(root: JSONObject): MutableSet<String> {
    val out = sortedSetOf<String>()
    val rules = root.optJSONArray("dns")?.optJSONArray("rules") ?: return out
    for (i in 0 until rules.length()) {
      val rule = rules.optJSONObject(i) ?: continue
      if (rule.optString("server") != "dns-direct") continue
      collectDomainsFromValue(rule.opt("domain"), out)
    }
    return out
  }

  private fun hasSingBoxOutboundTag(root: JSONObject, tag: String): Boolean {
    val outbounds = root.optJSONArray("outbounds") ?: return false
    return (0 until outbounds.length()).any { outbounds.optJSONObject(it)?.optString("tag") == tag }
  }

  /**
   * Same predicate as the daemon's `is_ipv4` (`ipv4_to_u32`): four decimal octets, each 0-255.
   * Used to pick the primary DNS server and to keep IPv4 literals out of the fakeip domain set.
   */
  private fun isIpv4(s: String): Boolean {
    val parts = s.trim().split('.')
    return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.all { it.isDigit() } && p.toInt() in 0..255 } // Rust u8 parse: digits only, no sign.
  }

  /**
   * mihomo reads the descriptor from the `tun.file-descriptor` field of its YAML config
   * (config.RawTun.FileDescriptor), not from argv. The value is only known after
   * VpnService.establish(), so it is written as the `%FD%` placeholder and substituted by
   * VpnEngineService before launch. auto-route/auto-detect-interface must be off: VpnService
   * already owns the interface and the routing table, letting mihomo touch either would fight it.
   */
  private fun writeMihomoConfig(program: String, profile: String, mixedPort: Int): File {
    val templatePath = profiles.profileDir(program, profile).resolve("config.yaml")
    val text = if (templatePath.isFile) {
      // Drop a root-style `tun:` block so its `device:` never survives into
      // non-root mode; the daemon's own block is appended below.
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
    if (!text.contains("mixed-port:")) sb.insert(0, "mixed-port: $mixedPort\n")
    // %FD% is replaced by VpnEngineService with the real VpnService descriptor.
    sb.appendLine("tun:")
    sb.appendLine("  enable: true")
    sb.appendLine("  stack: mixed")
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
      // The daemon's per-server setting is the source of the listen port and (for hysteria2) of
      // `log_level`; carry both so the non-root resolver does not have to re-read the file.
      return JSONObject().put("name", dir.name)
        .put("port", setting.optInt("port", setting.optInt("socks5_port", 0)))
        .put("log_level", setting.optString("log_level", "info"))
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
      // Same normalization the daemon applies before spawning (`normalize_singbox_config_for_t2s`):
      // the mixed inbound plus the DNS/route blocks. Without it the socks inbound and the DNS chain
      // are whatever the imported config happens to ship.
      val runtimeConfig = writeSingBoxConfig(config, port, setting)
      return Plan(program, name, NonRootBinaries.Engine.TUN2SOCKS,
        tunAddressFor(setting, program, name), packages, setting,
        "sing-box / $name (${server.optString("name")})",
        engineConfig = EngineExtra.Tun2SocksUpstream("socks5://127.0.0.1:$port"),
        upstreams = listOf(VpnEngineService.Companion.UpstreamEngine(
          binaries.ensureInstalled(NonRootBinaries.Engine.SING_BOX).absolutePath,
          listOf("run", "-c", runtimeConfig.absolutePath), port,
          profiles.logDir(program, name).resolve("sing-box.log").absolutePath)))
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
      val runtimeConfig = writeHysteria2Config(config, port, program, name)
      return Plan(program, name, NonRootBinaries.Engine.TUN2SOCKS,
        tunAddressFor(setting, program, name), packages, setting,
        "hysteria2 / $name (${server.optString("name")})",
        engineConfig = EngineExtra.Tun2SocksUpstream("socks5://127.0.0.1:$port"),
        upstreams = listOf(VpnEngineService.Companion.UpstreamEngine(
          binaries.ensureInstalled(NonRootBinaries.Engine.HYSTERIA2).absolutePath,
          listOf("--disable-update-check", "-f", "console", "-l", normalizeHysteria2LogLevel(server.optString("log_level")), "-c", runtimeConfig.absolutePath, "client"),
          port, profiles.logDir(program, name).resolve("hysteria2.log").absolutePath)))
    }
    return null
  }

  /**
   * Same normalization the daemon applies (`normalize_hysteria2_config_for_socks5`): drop every
   * inbound/tun/route key that would try to touch the network and pin the socks5 listener to
   * loopback:[port].
   */
  private fun writeHysteria2Config(template: File, port: Int, program: String, profile: String): File {
    val root = if (template.isFile) {
      runCatching { JSONObject(template.readText()) }.getOrNull() ?: JSONObject()
    } else JSONObject()
    for (key in listOf("http", "tcpForwarding", "udpForwarding", "tcpTProxy", "udpTProxy", "tcpRedirect", "tun", "inbounds", "outbounds", "route", "dns")) {
      root.remove(key)
    }
    val socks5 = root.optJSONObject("socks5") ?: JSONObject()
    root.remove("socks5")
    socks5.put("listen", "127.0.0.1:$port")
    socks5.put("disableUDP", false)
    root.put("socks5", socks5)

    val out = profiles.profileDir(program, profile).resolve("noroot/config.json")
    out.parentFile?.mkdirs()
    out.writeText(root.toString())
    return out
  }

  /**
   * Same clamp the daemon applies to a hysteria2 server's `log_level`
   * (hysteria2.rs `normalize_log_level`): an unknown value falls back to
   * `info` instead of being rejected by the binary.
   */
  private fun normalizeHysteria2LogLevel(value: String): String =
    when (value.trim().lowercase()) {
      "trace", "debug", "info", "warn", "error", "silent" -> value.trim().lowercase()
      else -> "info"
    }

  /**
   * tun2socks log level for the fd engine.
   *
   * The daemon stores this per program: sing-box / hysteria2 use
   * `tun2socks_loglevel`, mieru uses `tun2proxy_loglevel`, and the standalone
   * tun2socks engine uses `loglevel`. Accept all of them so a profile copied
   * from either side keeps its level, falling back to `info` (the daemon
   * default in every one of those files).
   */
  private fun tun2socksLogLevel(setting: JSONObject): String {
    val raw = setting.optString("tun2socks_loglevel")
      .ifBlank { setting.optString("tun2proxy_loglevel") }
      .ifBlank { setting.optString("loglevel") }
    return normalizeHysteria2LogLevel(raw)
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
   * already carries one (`tun_address`), otherwise falls back to an address
   * that is provably outside every daemon engine pool: the daemon hands out
   * `/30` networks per profile from per-engine bases (sing-box `172.31.240.0`,
   * hysteria2 `172.31.232.0`, mieru `172.31.252.0`, mihomo `198.18.140.0`,
   * tun2socks `198.18.100.0`, all `base + index*4`), and the old fallback
   * `172.31.242.2` was *inside* the sing-box pool; `172.31.225.2` sits below
   * the lowest base and is never generated.
   */
  private fun tunAddressFor(setting: JSONObject, program: String, profile: String): String {
    setting.optString("tun_address").takeIf { it.isNotBlank() }?.let { return it }
    return DEFAULT_TUN_ADDRESS
  }
}
