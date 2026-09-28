package com.android.zdtd.service.noroot

import android.content.Context
import android.os.Build
import com.android.zdtd.service.RootConfigManager
import java.io.File

/**
 * Detects whether the ZDT-D root module is actually usable on this device.
 *
 * The app supports two modes from a single APK:
 *  - root mode: talks to the `zdtd` daemon over 127.0.0.1:1006 (unchanged);
 *  - non-root mode: runs a userspace engine behind a standard [android.net.VpnService].
 *
 * Mode selection is automatic. The probe is deliberately conservative: it
 * requires the module directory to exist *and* the API token to be readable,
 * because a half-installed module is not usable either.
 */
object RootAvailability {

  const val MODULE_DIR = "/data/adb/modules/ZDT-D"
  const val MODULE_PROP = "$MODULE_DIR/module.prop"
  const val TOKEN_FILE = "$MODULE_DIR/api/token"

  enum class Mode { ROOT, NON_ROOT }

  data class Result(
    val mode: Mode,
    /** True when some root manager answered the su prompt at least once. */
    val suAnswered: Boolean,
    /** Module directory exists and contains a readable module.prop. */
    val modulePresent: Boolean,
    /** API token file exists and is readable with a non-empty first line. */
    val tokenReadable: Boolean,
  ) {
    val isRoot: Boolean get() = mode == Mode.ROOT
  }

  /**
   * Probes the device. Safe to call from any thread; the only slow part is the
   * optional su probe, which is bounded by libsu's own timeout.
   *
   * @param allowSuProbe when false, the su probe is skipped and only the
   * file-based signals are used. Useful for a fast first pass.
   */
  @Synchronized
  fun detect(context: Context, allowSuProbe: Boolean = true): Result {
    val modulePresent = modulePropText(context).isNotEmpty()
    val tokenReadable = tokenText(context).isNotEmpty()

    var suAnswered = false
    var moduleUsableViaRoot = false
    if (allowSuProbe && (!modulePresent || !tokenReadable)) {
      // /data/adb is normally unreadable to an ordinary app, even when the
      // module is installed and healthy. First obtain a root shell, then test
      // the module files through that shell instead of treating them as absent.
      val root = RootConfigManager(context)
      suAnswered = runCatching { root.isRootAvailable() }.getOrDefault(false)
      if (suAnswered) {
        moduleUsableViaRoot = runCatching {
          root.execRootSh(
            "test -s '$MODULE_PROP' && test -s '$TOKEN_FILE'"
          ).isSuccess
        }.getOrDefault(false)
      }
    }

    val mode = if ((modulePresent && tokenReadable) || moduleUsableViaRoot) {
      Mode.ROOT
    } else {
      Mode.NON_ROOT
    }
    return Result(mode, suAnswered, modulePresent, tokenReadable)
  }

  /** Reads /data/adb/modules/ZDT-D/module.prop without a shell when possible. */
  private fun modulePropText(context: Context): String =
    readFirstLines(File(MODULE_PROP), maxLines = 2)

  /** Reads the daemon API token without a shell when possible. */
  private fun tokenText(context: Context): String =
    readFirstLines(File(TOKEN_FILE), maxLines = 1)

  /**
   * Plain file read. On a rooted device the app usually has no permission to
   * read /data/adb directly, so [RootConfigManager.readApiToken] is the real
   * fallback used by the caller; this is only the fast pre-check.
   */
  private fun readFirstLines(file: File, maxLines: Int): String {
    return runCatching {
      if (!file.isFile) return ""
      file.useLines { lines ->
        lines.take(maxLines).joinToString("\n").trim()
      }
    }.getOrDefault("")
  }

  /** Human readable summary for the diagnostics screen / logs. */
  fun describe(r: Result): String = buildString {
    append("mode=").append(if (r.isRoot) "root" else "non-root")
    append(", module=").append(r.modulePresent)
    append(", token=").append(r.tokenReadable)
    if (r.suAnswered) append(", su=answered")
    append(", abi=").append(Build.SUPPORTED_ABIS.joinToString(","))
  }
}
