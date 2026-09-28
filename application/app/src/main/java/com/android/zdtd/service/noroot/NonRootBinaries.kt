package com.android.zdtd.service.noroot

import android.content.Context
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * Installs the engine binaries used in non-root mode into the app-private
 * `no_backup/bin` directory.
 *
 * The pattern (SHA-256 verified, atomic rename) is copied from
 * [com.android.zdtd.service.diagnostics.dpi.DpiDetectorBinary]: the binary can
 * survive an app update on disk, so we never trust the file blindly and always
 * compare against the bundled asset digest before executing it.
 *
 * In the root flow these binaries live in `/data/adb/modules/ZDT-D/bin`. In
 * non-root mode the app has no write access there, so a private copy is used.
 */
class NonRootBinaries(private val context: Context) {

  val binDir: File
    get() = File(context.noBackupFilesDir, "bin")

  /** Asset name -> on-disk file name for the engines that can run without root. */
  enum class Engine(val assetName: String, val fileName: String) {
    SING_BOX("sing-box", "sing-box"),
    TUN2SOCKS("tun2socks", "tun2socks"),
    BYEDPI("byedpi", "byedpi"),
    DNSCRYPT("dnscrypt", "dnscrypt-proxy"),
    D2S("d2s", "d2s"),
    HYSTERIA2("hysteria2", "hysteria2"),
    WIREPROXY("wireproxy", "wireproxy"),
    // CI packages the tor build as "torproxy" (see build.yml build_torproxy).
    TOR("torproxy", "torproxy"),
    LYREBIRD("lyrebird", "lyrebird"),
    MIHOMO("mihomo", "mihomo"),
    MIERU("mieru", "mieru");

    /** True when this binary accepts a VpnService fd in the current build. */
    val supportsTunFd: Boolean
      get() = when (this) {
        SING_BOX, TUN2SOCKS, MIHOMO -> true
        // These are plain listeners; in non-root mode they run as a local
        // SOCKS/HTTP upstream that the TUN engine forwards to.
        else -> false
      }
  }

  /** Returns the installed path, installing/updating it first if needed. */
  fun ensureInstalled(engine: Engine): File {
    val target = File(binDir, engine.fileName)
    val parent = target.parentFile ?: error("invalid bin dir: $target")
    if (!parent.exists() && !parent.mkdirs()) error("cannot create $parent")

    val assetPath = assetPath(engine)
    val assetBytes = try {
      context.assets.open(assetPath).use { it.readBytes() }
    } catch (e: Throwable) {
      // Phase 1: the engine binaries are not bundled in the APK assets yet
      // (see docs/NON_ROOT.md, phase 2). Report clearly instead of crashing.
      throw MissingEngineException(engine, assetPath, e)
    }

    val assetSha = sha256(assetBytes)
    if (!target.exists() || sha256OrNull(target) != assetSha) {
      val tmp = File(parent, engine.fileName + ".tmp")
      tmp.outputStream().use { it.write(assetBytes) }
      tmp.setReadable(true, true)
      tmp.setWritable(true, true)
      tmp.setExecutable(true, true)

      if (target.exists() && !target.delete()) {
        tmp.delete()
        error("cannot replace old binary: $target")
      }
      if (!tmp.renameTo(target)) {
        tmp.delete()
        error("cannot install binary: $target")
      }
    }

    target.setReadable(true, true)
    target.setWritable(true, true)
    target.setExecutable(true, true)
    return target
  }

  /** Returns true when the engine binary is present in the APK assets. */
  fun isBundled(engine: Engine): Boolean = try {
    context.assets.list(assetDir(engine))?.contains(engine.assetName) == true
  } catch (_: Throwable) {
    false
  }

  private fun assetDir(engine: Engine): String = "noroot-binaries/" + preferredAbi()

  private fun assetPath(engine: Engine): String = assetDir(engine) + "/" + engine.assetName

  private fun preferredAbi(): String = when {
    Build.SUPPORTED_ABIS.any { it == "arm64-v8a" } -> "arm64-v8a"
    Build.SUPPORTED_ABIS.any { it == "armeabi-v7a" } -> "armeabi-v7a"
    else -> Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
  }

  private fun sha256(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(bytes).toHex()
  }

  private fun sha256OrNull(file: File): String? {
    if (!file.isFile) return null
    return runCatching {
      val digest = MessageDigest.getInstance("SHA-256")
      file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
          val read = input.read(buffer)
          if (read <= 0) break
          digest.update(buffer, 0, read)
        }
      }
      digest.digest().toHex()
    }.getOrNull()
  }

  private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

  /** Raised when an engine binary is not (yet) shipped in the APK. */
  class MissingEngineException(
    val engine: Engine,
    val assetPath: String,
    cause: Throwable,
  ) : Exception("engine binary not bundled: $assetPath (${cause.message ?: cause})", cause)
}
