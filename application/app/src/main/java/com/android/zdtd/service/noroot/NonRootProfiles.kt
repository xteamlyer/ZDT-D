package com.android.zdtd.service.noroot

import android.content.Context
import com.android.zdtd.service.api.ApiModels
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder

/**
 * Reads the ZDT-D profile layout in non-root mode.
 *
 * The root daemon keeps everything under
 * `/data/adb/modules/ZDT-D/working_folder`. This class mirrors the *exact same*
 * tree inside the app-private directory so that `.zdtb` backups and manual
 * profile copies are interchangeable between the root and non-root versions.
 *
 * Layout (identical to the daemon):
 * ```
 * <filesDir>/working_folder/<program>/active.json
 * <filesDir>/working_folder/<program>/<profile>/setting.json
 * <filesDir>/working_folder/<program>/<profile>/app/uid/user_program
 * <filesDir>/working_folder/<program>/<profile>/log/(log files)
 * ```
 */
class NonRootProfiles(context: Context) {

  private val rootDir: File = File(context.filesDir, "working_folder")

  /** Supported non-root programs. See [docs/NON_ROOT.md]. */
  enum class Program(val id: String) {
    SINGBOX("sing-box"),
    MIHOMO("mihomo"),
    TUN2SOCKS("tun2socks"),
    HYSTERIA2("hysteria2"),
    WIREPROXY("wireproxy"),
    BYEDPI("byedpi"),
    DNSCRYPT("dnscrypt"),
    TOR("tor"),
    MIERU("mieru"),
    TGWSPROXY("tgwsproxy"),
    MYPROXY("myproxy"),
    MYPROGRAM("myprogram");

    companion object {
      fun fromId(id: String): Program? = entries.firstOrNull { it.id == id }
    }
  }

  /** Mirror of the daemon `active.json`: `{ "profiles": { name: { enabled } } }`. */
  data class ActiveProfiles(val profiles: Map<String, Boolean>) {
    val enabled: List<String>
      get() = profiles.filterValues { it }.keys.toList()
  }

  val workingFolder: File get() = rootDir

  fun programDir(program: String): File = File(rootDir, program)

  fun profileDir(program: String, profile: String): File =
    File(programDir(program), profile)

  fun activeFile(program: String): File = File(programDir(program), "active.json")

  fun settingFile(program: String, profile: String): File =
    File(profileDir(program, profile), "setting.json")

  /** The per-app list. Comments (starting with '#') and blank lines are ignored. */
  fun appListFile(program: String, profile: String): File =
    File(profileDir(program, profile), "app/uid/user_program")

  fun logDir(program: String, profile: String): File =
    File(profileDir(program, profile), "log")

  // ----- active.json -----

  /** Reads active.json, tolerating a missing/corrupt file (empty result). */
  fun readActive(program: String): ActiveProfiles {
    val text = runCatching { activeFile(program).readText() }.getOrNull() ?: return ActiveProfiles(emptyMap())
    return parseActive(text)
  }

  fun parseActive(text: String): ActiveProfiles {
    val map = linkedMapOf<String, Boolean>()
    runCatching {
      val root = JSONObject(text)
      val profiles = root.optJSONObject("profiles") ?: return@runCatching
      val keys = profiles.keys()
      while (keys.hasNext()) {
        val name = keys.next()
        val st = profiles.optJSONObject(name)
        map[name] = st?.optBoolean("enabled", false) ?: false
      }
    }
    return ActiveProfiles(map)
  }

  /** Writes active.json atomically so a crash mid-write cannot corrupt it. */
  fun writeActive(program: String, profiles: Map<String, Boolean>) {
    val root = JSONObject()
    val obj = JSONObject()
    profiles.forEach { (name, enabled) -> obj.put(name, JSONObject().put("enabled", enabled)) }
    root.put("profiles", obj)
    writeAtomic(activeFile(program), root.toString())
  }

  // ----- setting.json -----

  /** Reads setting.json as a raw object; the caller interprets program fields. */
  fun readSetting(program: String, profile: String): JSONObject? {
    val text = runCatching { settingFile(program, profile).readText() }.getOrNull() ?: return null
    return runCatching { JSONObject(text) }.getOrNull()
  }

  fun writeSetting(program: String, profile: String, json: JSONObject) {
    writeAtomic(settingFile(program, profile), json.toString())
  }

  // ----- per-app list -----

  /**
   * Returns the list of package names to route, or null when the app list is
   * empty (the daemon treats an empty list as a start error, so we do too).
   */
  fun readAppList(program: String, profile: String): List<String>? {
    val file = appListFile(program, profile)
    if (!file.isFile) return null
    val out = linkedSetOf<String>()
    file.useLines { lines ->
      lines.forEach { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEach
        out.add(line)
      }
    }
    return out.takeIf { it.isNotEmpty() }?.toList()
  }

  /** Reads root-style app lists used by global programs such as Tor. */
  fun readLegacyUidList(file: File): List<String>? {
    if (!file.isFile) return null
    val out = linkedSetOf<String>()
    file.useLines { lines -> lines.forEach { raw ->
      val line = raw.trim()
      if (line.isNotEmpty() && !line.startsWith("#")) out.add(line.substringBefore('=').trim())
    } }
    return out.takeIf { it.isNotEmpty() }?.toList()
  }

  fun writeAppList(program: String, profile: String, packages: Collection<String>) {
    val text = buildString {
      packages.forEach { appendLine(it) }
    }
    writeAtomic(appListFile(program, profile), text)
  }

  // ----- daemon API path fallback -----

  /**
   * Maps a daemon API path to its file inside the app-private mirror, or null
   * when the path is not one the non-root engine understands.
   *
   * This is what keeps the profile editors usable without the daemon: the
   * screens all talk in API paths (`/api/programs/<p>/profiles/<n>/setting`),
   * so instead of rewriting every screen we resolve those paths against the
   * same tree the daemon would have used.
   *
   * File names mirror the daemon exactly (`setting.json`, `proxy.json`,
   * `config.yaml`, `config.json`, `config.conf`, `client.ovpn`, `client.conf`,
   * `app/uid/user_program`) so a profile copied between root and non-root
   * installs keeps working.
   */
  fun fileForApiPath(path: String): File? {
    val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
    if (parts.size < 2 || parts[0] != "api") return null
    // /api/programs/<program>/...
    if (parts.size < 3 || parts[1] != "programs") return null
    val program = URLDecoder.decode(parts[2], "UTF-8")

    // /api/programs/<program>/profiles/<profile>/<leaf>...
    if (parts.size >= 5 && parts[3] == "profiles") {
      val profile = URLDecoder.decode(parts[4], "UTF-8")
      if (!isSafeProfileName(profile)) return null
      val rest = parts.drop(5)
      return profileFileFor(program, profile, rest)
    }

    // /api/programs/<program>/<leaf> — program-level files (tor/torrc,
    // sing-box/setting, dnscrypt/config, tgwsproxy/command, ...).
    return programFileFor(program, parts.drop(3))
  }

  private fun profileFileFor(program: String, profile: String, rest: List<String>): File? {
    val dir = profileDir(program, profile)
    return when {
      rest.isEmpty() -> dir
      rest == listOf("setting") -> settingFile(program, profile)
      rest == listOf("proxy") -> File(dir, "proxy.json")
      rest == listOf("apps", "user") -> appListFile(program, profile)
      // Per-server setting: sing-box/hysteria2/wireproxy keep `server/<name>/setting.json`.
      rest.size == 3 && rest[0] == "servers" && rest[2] == "setting" -> {
        if (!isSafeProfileName(rest[1])) return null
        File(dir, "server/${rest[1]}/setting.json")
      }
      // sing-box / wireproxy / hysteria2 per-server config.
      rest.size == 3 && rest[0] == "servers" && rest[2] == "config" -> {
        if (!isSafeProfileName(rest[1])) return null
        when (program) {
          "sing-box" -> File(dir, "server/${rest[1]}/config.json")
          "wireproxy" -> File(dir, "server/${rest[1]}/config.conf")
          "hysteria2" -> File(dir, "server/${rest[1]}/config.json")
          else -> null
        }
      }
      // Named config file per program.
      rest == listOf("config") -> when (program) {
        "sing-box" -> File(dir, "config.json")
        "mihomo" -> File(dir, "config.yaml")
        "mieru" -> File(dir, "config.json")
        "openvpn" -> File(dir, "client.ovpn")
        "amneziawg" -> File(dir, "client.conf")
        "wireproxy" -> File(dir, "config.conf")
        "hysteria2" -> File(dir, "config.json")
        "tun2socks" -> File(dir, "config.json")
        "myvpn" -> File(dir, "config.json")
        "myprogram" -> File(dir, "config.json")
        // nfqws/nfqws2/dpitunnel/byedpi keep their args in `config/config.txt`.
        "nfqws", "nfqws2", "dpitunnel", "byedpi" -> File(dir, "config/config.txt")
        else -> null
      }
      else -> null
    }
  }

  private fun programFileFor(program: String, rest: List<String>): File? {
    if (rest.isEmpty()) return programDir(program)
    return when (program) {
      "tor" -> when (rest.joinToString("/")) {
        "torrc" -> File(programDir(program), "torrc")
        "setting" -> File(programDir(program), "setting.json")
        else -> null
      }
      "sing-box" -> if (rest == listOf("setting")) File(programDir(program), "setting.json") else null
      "dnscrypt" -> if (rest == listOf("config")) File(programDir(program), "setting/dnscrypt-proxy.toml") else null
      // tgwsproxy/command is a *preview* the daemon generates on the fly, not a
      // file; return an empty payload so the screen renders rather than erroring.
      "tgwsproxy" -> if (rest == listOf("command")) null else null
      else -> null
    }
  }

  /**
   * Builds the JSON the daemon returns for `/api/programs/<p>/profiles/<n>/servers`,
   * purely from the on-disk `server/<name>/setting.json` entries. Returns null
   * when the profile directory does not exist, so callers report a load error
   * the same way they would against the daemon.
   */
  fun readServersJson(program: String, profile: String): JSONObject? {
    val dir = profileDir(program, profile)
    if (!dir.isDirectory) return null
    val arr = JSONArray()
    dir.resolve("server")
      .listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
      ?.sortedBy { it.name }
      ?.forEach { serverDir ->
        val setting = runCatching { JSONObject(serverDir.resolve("setting.json").readText()) }.getOrNull()
        arr.put(JSONObject().put("name", serverDir.name).put("setting", setting ?: JSONObject()))
      }
    return JSONObject().put("servers", arr)
  }

  /**
   * Creates a per-server directory with an empty config, mirroring the daemon's
   * `POST /api/programs/<p>/profiles/<n>/servers`. Returns the name, or null
   * when the name is unsafe or already taken.
   */
  fun createServer(program: String, profile: String, server: String): String? {
    if (!isSafeProfileName(server)) return null
    val dir = profileDir(program, profile).resolve("server").resolve(server.trim())
    if (dir.isDirectory) return null
    dir.resolve("log").mkdirs()
    val configName = if (program == "wireproxy") "config.conf" else "config.json"
    writeAtomic(dir.resolve(configName), "")
    writeAtomic(dir.resolve("setting.json"), JSONObject().put("enabled", false).put("port", 0).toString())
    return server.trim()
  }

  /** Removes a per-server directory. Returns false on an unsafe name. */
  fun deleteServer(program: String, profile: String, server: String): Boolean {
    if (!isSafeProfileName(server)) return false
    val dir = profileDir(program, profile).resolve("server").resolve(server.trim())
    if (!dir.isDirectory) return false
    return runCatching { dir.deleteRecursively() }.getOrDefault(false)
  }

  /**
   * Builds the JSON the daemon returns for `/api/programs/<p>/profiles`, from
   * the on-disk `active.json`. Used by the subscription screens' profile
   * pickers. Returns null when the program directory does not exist.
   */
  fun readProfileListJson(program: String): JSONObject? {
    val dir = programDir(program)
    if (!dir.isDirectory) return null
    val arr = JSONArray()
    readActive(program).profiles.forEach { (name, enabled) ->
      arr.put(JSONObject().put("name", name).put("enabled", enabled))
    }
    return JSONObject().put("profiles", arr)
  }

  /**
   * Builds the JSON the daemon returns for `/api/programs/myprogram/profiles/<n>/bin`,
   * listing `<profile>/bin`. Returns null when the profile directory is absent.
   */
  fun readMyProgramBinList(profile: String): JSONObject? {
    val dir = profileDir("myprogram", profile)
    if (!dir.isDirectory) return null
    val arr = JSONArray()
    dir.resolve("bin").listFiles { f -> f.isFile }
      ?.sortedBy { it.name }
      ?.forEach { arr.put(JSONObject().put("name", it.name).put("size", it.length())) }
    return JSONObject().put("files", arr)
  }

  /** Copies a user binary into `<profile>/bin`, mirroring the daemon upload. */
  fun uploadMyProgramBin(profile: String, filename: String, source: File): Boolean {
    if (!isSafeProfileName(profile)) return false
    val name = filename.trim()
    if (name.isEmpty() || name.contains('/') || name.contains('\\') || name.contains("..")) return false
    val target = profileDir("myprogram", profile).resolve("bin").resolve(name)
    return runCatching {
      target.parentFile?.mkdirs()
      source.copyTo(target, overwrite = true)
      true
    }.getOrDefault(false)
  }

  /** Removes a user binary from `<profile>/bin`. */
  fun deleteMyProgramBin(profile: String, filename: String): Boolean {
    if (!isSafeProfileName(profile)) return false
    val name = filename.trim()
    if (name.isEmpty() || name.contains('/') || name.contains('\\') || name.contains("..")) return false
    val target = profileDir("myprogram", profile).resolve("bin").resolve(name)
    if (!target.isFile) return false
    return runCatching { target.delete() }.getOrDefault(false)
  }

  // ----- strategic variants (byedpi) -----

  /**
   * Lists the built-in strategy files for [program], mirroring the daemon's
   * `/api/strategicvar/<program>`. Only the four programs the daemon allows are
   * meaningful; in non-root mode only `byedpi` is an engine the userspace
   * runtime can actually start, so the others return an empty list.
   */
  fun listStrategicVariants(program: String): List<ApiModels.StrategyVariant> {
    if (!isAllowedStrategicProgram(program)) return emptyList()
    val dir = strategicVarDir(program)
    if (!dir.isDirectory) return emptyList()
    return dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
      ?.sortedBy { it.name }
      ?.map { ApiModels.StrategyVariant(name = it.name, sha256 = null) }
      ?: emptyList()
  }

  /**
   * Copies a built-in strategy into the profile's `config/config.txt`, exactly
   * like the daemon's `/api/strategicvar/apply`. Returns false when the program,
   * profile or strategy is unknown.
   */
  fun applyStrategicVariant(program: String, profile: String, file: String): Boolean {
    if (!isAllowedStrategicProgram(program) || !isSafeProfileName(profile)) return false
    val name = file.trim()
    if (name.isEmpty() || !name.endsWith(".txt") || name.contains('/') || name.contains('\\')) return false
    val src = strategicVarDir(program).resolve(name)
    if (!src.isFile) return false
    val dst = profileDir(program, profile).resolve("config/config.txt")
    return runCatching {
      dst.parentFile?.mkdirs()
      src.copyTo(dst, overwrite = true)
      true
    }.getOrDefault(false)
  }

  private fun isAllowedStrategicProgram(program: String): Boolean =
    program == "byedpi"

  private fun strategicVarDir(program: String): File =
    File(rootDir.parentFile, "strategic/strategicvar/$program")

  /** Reads a JSON API path locally; returns null when missing or unreadable. */
  fun readJsonPath(path: String): JSONObject? {
    // Synthesized listings (the daemon builds these the same way) rather than a
    // single file on disk.
    val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
    if (parts.size == 4 && parts[0] == "api" && parts[1] == "programs" && parts[3] == "profiles") {
      return readProfileListJson(URLDecoder.decode(parts[2], "UTF-8"))
    }
    if (parts.size == 6 && parts[0] == "api" && parts[1] == "programs" &&
      parts[3] == "profiles" && parts[5] == "servers") {
      return readServersJson(URLDecoder.decode(parts[2], "UTF-8"), URLDecoder.decode(parts[4], "UTF-8"))
    }
    // myprogram per-profile binary list.
    if (parts.size == 6 && parts[0] == "api" && parts[1] == "programs" &&
      parts[2] == "myprogram" && parts[3] == "profiles" && parts[5] == "bin") {
      return readMyProgramBinList(URLDecoder.decode(parts[4], "UTF-8"))
    }
    val file = fileForApiPath(path) ?: return null
    if (!file.isFile) return null
    return runCatching { JSONObject(file.readText()) }.getOrNull()
  }

  /** Writes a JSON API path locally, creating parent dirs. Returns false on failure. */
  fun writeJsonPath(path: String, obj: JSONObject): Boolean {
    val file = fileForApiPath(path) ?: return false
    return runCatching { writeAtomic(file, obj.toString()); true }.getOrDefault(false)
  }

  /**
   * Reads a text API path locally; returns null when the path is unknown, and
   * an empty string when it is simply absent (the daemon's `read_text_or_empty`
   * behaves the same way, so the editors see no error for a new profile).
   */
  fun readTextPath(path: String): String? {
    val file = fileForApiPath(path) ?: return null
    if (!file.isFile) return ""
    return runCatching { file.readText() }.getOrNull()
  }

  /** Writes a text API path locally, creating parent dirs. Returns false on failure. */
  fun writeTextPath(path: String, content: String): Boolean {
    val file = fileForApiPath(path) ?: return false
    return runCatching { writeAtomic(file, content); true }.getOrDefault(false)
  }

  // ----- helpers -----

  /** Lists profile names that exist on disk (enabled or not). */
  fun listProfiles(program: String): List<String> {
    val dir = programDir(program)
    if (!dir.isDirectory) return emptyList()
    return dir.listFiles { f -> f.isDirectory && settingFile(program, f.name).isFile }
      ?.map { it.name }
      ?.sorted()
      ?: emptyList()
  }

  /**
   * Builds the same [ApiModels.Program] list the daemon returns from
   * `/api/programs`, but purely from the app-private mirror. This is what keeps
   * the profile screens working in non-root mode: the UI consumes `Program`
   * regardless of mode, so synthesizing it locally makes the daemon
   * unnecessary for browsing, enabling and creating profiles.
   */
  fun listPrograms(): List<ApiModels.Program> = Program.entries.map { p ->
    val active = readActive(p.id)
    ApiModels.Program(
      id = p.id,
      name = p.id,
      enabled = active.enabled.isNotEmpty(),
      profiles = listProfiles(p.id).map { name ->
        ApiModels.Profile(name = name, enabled = active.profiles[name] ?: false)
      },
    )
  }

  /**
   * Creates a profile directory with a minimal `setting.json`, mirroring what
   * the daemon's `/api/new/profile` produces. Returns the created name, or null
   * when the name is unsafe or the directory already exists.
   */
  fun createProfile(program: String, requestedName: String? = null): String? {
    if (!isSafeProfileName(requestedName)) return null
    val name = requestedName.trim()
    val dir = profileDir(program, name)
    if (dir.isDirectory) return null
    ensureProfileLayout(program, name)
    writeSetting(program, name, JSONObject())
    return name
  }

  /** Toggles a profile in `active.json` without touching the daemon. */
  fun setProfileEnabled(program: String, profile: String, enabled: Boolean): Boolean {
    if (!isSafeProfileName(profile)) return false
    val current = readActive(program).profiles.toMutableMap()
    current[profile] = enabled
    return runCatching { writeActive(program, current); true }.getOrDefault(false)
  }

  fun ensureProfileLayout(program: String, profile: String) {
    logDir(program, profile).mkdirs()
    val appIn = File(profileDir(program, profile), "app/uid")
    appIn.mkdirs()
    val appOut = File(profileDir(program, profile), "app/out")
    appOut.mkdirs()
  }

  /**
   * Deletes a profile directory. Returns false when the name could escape the
   * program directory (the daemon applies the same kind of guard before it
   * removes a profile).
   */
  fun deleteProfile(program: String, profile: String): Boolean {
    if (!isSafeProfileName(profile)) return false
    val dir = profileDir(program, profile)
    if (!dir.isDirectory) return false
    return runCatching { dir.deleteRecursively() }.getOrDefault(false)
  }

  /** True when [name] is a plain, single-level directory name. */
  private fun isSafeProfileName(name: String?): Boolean {
    val trimmed = name?.trim().orEmpty()
    if (trimmed.isEmpty() || trimmed.length > 64) return false
    if (trimmed.contains('/') || trimmed.contains('\\') || trimmed.contains("..")) return false
    return trimmed.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
  }

  private fun writeAtomic(target: File, text: String) {
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, target.name + ".tmp")
    tmp.writeText(text)
    if (target.exists() && !target.delete()) {
      tmp.delete()
      error("cannot replace ${target.absolutePath}")
    }
    if (!tmp.renameTo(target)) {
      tmp.delete()
      error("cannot write ${target.absolutePath}")
    }
  }

  @Suppress("unused")
  private fun JSONArray.toStringList(): List<String> = buildList {
    for (i in 0 until length()) add(optString(i))
  }
}
