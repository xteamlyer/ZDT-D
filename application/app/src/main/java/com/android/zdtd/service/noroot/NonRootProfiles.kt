package com.android.zdtd.service.noroot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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

  fun ensureProfileLayout(program: String, profile: String) {
    logDir(program, profile).mkdirs()
    val appIn = File(profileDir(program, profile), "app/uid")
    appIn.mkdirs()
    val appOut = File(profileDir(program, profile), "app/out")
    appOut.mkdirs()
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
