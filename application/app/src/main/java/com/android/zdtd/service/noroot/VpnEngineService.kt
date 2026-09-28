package com.android.zdtd.service.noroot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.android.zdtd.service.MainActivity
import com.android.zdtd.service.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The non-root tunnel.
 *
 * The root daemon creates its TUN interfaces itself through `/dev/tun` and then
 * binds app UIDs to them via Android `netd`. Without root neither is possible,
 * so this service takes the standard Android route instead: it asks
 * [VpnService] for a TUN descriptor and hands the fd to the userspace engine
 * that is spawned by [NonRootEngine].
 *
 * Contract:
 *  - The service lifetime == the tunnel lifetime. If the engine dies, the
 *    service destroys the tunnel and stops itself (see [EngineWatcher]).
 *  - Only one tunnel at a time. VpnService allows a single session per app,
 *    which also matches the daemon's "one tun per profile" constraint.
 *  - Per-app routing uses [Builder.addAllowedApplication], which is the
 *    non-root equivalent of the daemon's UID list.
 */
class VpnEngineService : VpnService() {

  private var tunnel: ParcelFileDescriptor? = null
  private var engineProcess: Process? = null
  private var upstreamProcess: Process? = null
  private val upstreamProcesses = mutableListOf<Process>()
  private var watcher: Thread? = null
  @Volatile private var tunnelAddress: String = DEFAULT_TUN_ADDRESS

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    // Published from onCreate/onDestroy so callers can query the tunnel state
    // without the deprecated ActivityManager.getRunningServices().
    runningRef.set(true)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_START -> {
        val cfg = EngineConfig.fromIntent(intent)
        tunnelAddress = cfg.tunAddress
        startForeground(NOTIFICATION_ID, buildNotification(cfg.label))
        startTunnel(cfg)
      }
      ACTION_STOP -> {
        stopTunnel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
      }
    }
    return START_NOT_STICKY
  }

  /**
   * Establishes the TUN and launches the engine with the descriptor.
   *
   * Returns false when the user has not granted the VPN consent (or the system
   * refused the builder); the caller then shows the system consent dialog.
   */
  private fun startTunnel(cfg: EngineConfig): Boolean {
    if (tunnel != null) {
      Log.i(TAG, "tunnel already established; restarting engine only")
      stopEngine()
    }

    val builder = Builder()
      .setSession(SESSION_NAME)
      .setMtu(MTU)
      .addAddress(cfg.tunAddress, 32)
      // The engine is a local proxy: route only what it must handle. Keeping
      // the route scope narrow avoids a full-device VPN (and the Android
      // "always-on VPN" prompt) for what is essentially a local bypass tunnel.
      .addRoute(cfg.tunAddress, 32)

    // Per-app routing: the non-root equivalent of the daemon's uid list.
    // An empty list means "all apps" (VpnService default).
    val packages = cfg.packages
    if (packages.isNotEmpty()) {
      // Only the listed apps go through the tunnel.
      packages.forEach { pkg ->
        runCatching { builder.addAllowedApplication(pkg) }
          .onFailure { Log.w(TAG, "cannot add allowed application: $pkg") }
      }
      // Everything else must bypass the tunnel, otherwise we would silently
      // create a full-device VPN for a per-app profile.
      builder.addDisallowedApplication(packageName)
    }

    // Establish may return null if the user has not consented (returned via
    // Activity.onActivityResult with RESULT_OK) or the system rejected us.
    val pfd = runCatching { builder.establish() }.getOrNull()
    if (pfd == null) {
      Log.e(TAG, "VpnService.Builder.establish() returned null (consent missing?)")
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
      return false
    }

    tunnel = pfd
    Log.i(TAG, "tunnel established fd=${pfd.detachFd()} addr=${cfg.tunAddress} apps=${packages.size}")

    return runCatching { launchEngine(cfg, pfd) }
      .onFailure { Log.e(TAG, "engine launch failed", it) }
      .isSuccess
  }

  /** Spawns the engine binary, passing the TUN fd. */
  private fun launchEngine(cfg: EngineConfig, pfd: ParcelFileDescriptor): Boolean {
    val bin = cfg.binary ?: run {
      Log.e(TAG, "engine binary path is missing")
      return false
    }
    val binaryFile = File(bin)
    if (!binaryFile.isFile) {
      Log.e(TAG, "engine binary not found: $bin")
      return false
    }

    val fd = pfd.detachFd()

    // sing-box vpn mode: sing-box itself runs as a plain socks server (no fd),
    // and tun2socks is the process that consumes the descriptor. The daemon
    // does the same pair in singbox.rs, waiting for the port in between.
    val upstreams = if (cfg.upstreams.isNotEmpty()) cfg.upstreams else listOfNotNull(cfg.upstream)
    for (upstream in upstreams) {
      val up = runCatching { spawnUpstream(upstream) }
        .onFailure { Log.e(TAG, "upstream engine failed to start", it) }
        .getOrNull() ?: return false
      upstreamProcesses += up
      if (upstreamProcess == null) upstreamProcess = up
      if (upstream.port > 0 && !waitTcpPort("127.0.0.1", upstream.port, PORT_WAIT_MS)) {
        Log.e(TAG, "upstream port ${upstream.port} never came up")
        return false
      }
    }

    // mihomo reads the descriptor from its YAML config rather than argv; the
    // %FD% placeholder we wrote in NonRootEngine is resolved here.
    cfg.configPath?.let { resolveFdInFile(File(it), fd) }

    val args = ArrayList<String>().apply {
      add(bin)
      // The root daemon uses `-device tun://<name>` (it owns /dev/tun). Without
      // root we pass the descriptor VpnService gave us instead.
      addAll(cfg.buildArgs(fd))
    }

    val logFile = File(cfg.logPath)
    logFile.parentFile?.mkdirs()
    val logStream = logFile.outputStream()

    val pb = ProcessBuilder(args).apply {
      redirectErrorStream(true)
      // Detached so the fd stays open even if this process group is reaped.
      redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
    }
    pb.environment()["ZDT_TUN_FD"] = fd.toString()

    val proc = pb.start()
    // Pipe stdout/stderr into the profile log, same as the daemon does.
    Thread {
      runCatching {
        proc.inputStream.use { input ->
          logStream.use { output -> input.copyTo(output) }
        }
      }
    }.apply { isDaemon = true; name = "zdtd-noroot-engine-log" }.start()

    engineProcess = proc

    // Keep the service alive exactly as long as the engine lives. If the
    // binary exits (crash, misconfiguration), the tunnel is torn down so we
    // never leave the device with a dead-but-established VPN.
    watcher = Thread {
      val code = proc.waitFor()
      Log.i(TAG, "engine exited code=$code")
      stopTunnel()
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
    }.apply { isDaemon = true; name = "zdtd-noroot-engine-watcher" }
    watcher?.start()

    return true
  }

  /**
   * Starts the upstream process of a two-process plan (sing-box as a socks
   * server). Its output goes to the same per-profile log directory.
   */
  private fun spawnUpstream(up: UpstreamEngine): Process {
    val logFile = File(up.logPath)
    logFile.parentFile?.mkdirs()
    val logStream = logFile.outputStream()

    val pb = ProcessBuilder(up.binary, *up.args.toTypedArray()).apply {
      environment().putAll(up.environment)
      redirectErrorStream(true)
      redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
    }
    val proc = pb.start()
    Thread {
      runCatching {
        proc.inputStream.use { input ->
          logStream.use { output -> input.copyTo(output) }
        }
      }
    }.apply { isDaemon = true; name = "zdtd-noroot-upstream-log" }.start()
    return proc
  }

  /**
   * Polls a local TCP port until it accepts a connection.
   *
   * Mirrors the daemon's `wait_tcp_port`: the socks upstream must be listening
   * before tun2socks dials it, otherwise the tunnel comes up dead.
   */
  private fun waitTcpPort(host: String, port: Int, timeoutMs: Long): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      runCatching {
        java.net.Socket().use { s ->
          s.connect(java.net.InetSocketAddress(host, port), 500)
          return true
        }
      }
      runCatching { Thread.sleep(200) }
    }
    return false
  }

  /**
   * Replaces every `%FD%` occurrence in [file] with [fd], in place.
   *
   * Only used for engines that take the descriptor from their config file
   * (mihomo's `tun.file-descriptor`). Returns the same file for convenience.
   */
  private fun resolveFdInFile(file: File, fd: Int): File = run {
    if (!file.isFile) return@run file
    val text = file.readText()
    if (!text.contains(EngineConfig.FD_PLACEHOLDER)) return@run file
    file.writeText(text.replace(EngineConfig.FD_PLACEHOLDER, fd.toString()))
    file
  }

  private fun stopEngine() {
    engineProcess?.let { proc ->
      runCatching { proc.destroy() }
      engineProcess = null
    }
    upstreamProcesses.forEach { proc -> runCatching { proc.destroy() } }
    upstreamProcesses.clear()
    upstreamProcess = null
  }

  private fun stopTunnel() {
    stopEngine()
    watcher?.let { runCatching { it.interrupt() } }
    watcher = null
    tunnel?.let { pfd ->
      runCatching { pfd.close() }
      tunnel = null
    }
  }

  // ----- foreground notification -----

  private fun buildNotification(label: String): Notification {
    ensureChannel()
    val intent = Intent(this, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val pending = PendingIntent.getActivity(
      this, 0, intent,
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    val text = if (label.isNotEmpty()) label else getString(R.string.noroot_notification_text_default)
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_qs_tile)
      .setContentTitle(getString(R.string.app_name))
      .setContentText(text)
      .setOngoing(true)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .setContentIntent(pending)
      .build()
  }

  private fun ensureChannel() {
    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    if (nm.getNotificationChannel(CHANNEL_ID) != null) return
    nm.createNotificationChannel(
      NotificationChannel(
        CHANNEL_ID,
        getString(R.string.noroot_channel_name),
        NotificationManager.IMPORTANCE_LOW
      ).apply { description = getString(R.string.noroot_channel_desc) }
    )
  }

  override fun onDestroy() {
    stopTunnel()
    runningRef.set(false)
    super.onDestroy()
  }

  /**
   * Called by the system when another app wants to take over the VPN. We
   * refuse while our engine is running so the bypass tunnel is not silently
   * displaced by a random VPN the user starts.
   */
  override fun onRevoke() {
    Log.i(TAG, "onRevoke: tunnel displaced, stopping engine")
    stopTunnel()
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  companion object {
    private const val TAG = "ZDTD-VpnEngine"
    private const val CHANNEL_ID = "zdtd_noroot_engine"
    private const val NOTIFICATION_ID = 1017
    private const val SESSION_NAME = "ZDT-D"
    private const val MTU = 1500
    private const val PORT_WAIT_MS = 20_000L
    const val DEFAULT_TUN_ADDRESS = "172.31.240.2"

    const val ACTION_START = "com.android.zdtd.service.action.NOROOT_START"
    const val ACTION_STOP = "com.android.zdtd.service.action.NOROOT_STOP"

    /**
     * True while the service is alive. Set from [onCreate]/[onDestroy] — using a
     * static flag avoids the deprecated and unreliable
     * `ActivityManager.getRunningServices`, which only reports the caller's own
     * process on modern Android.
     */
    private val runningRef = java.util.concurrent.atomic.AtomicBoolean(false)

    /** True while the non-root tunnel is established. */
    @JvmStatic
    fun isRunning(): Boolean = runningRef.get()

    private const val EXTRA_LABEL = "label"
    private const val EXTRA_TUN_ADDRESS = "tun_address"
    private const val EXTRA_PACKAGES = "packages"
    private const val EXTRA_BINARY = "binary"
    private const val EXTRA_ARGS = "args"
    private const val EXTRA_LOG_PATH = "log_path"
    private const val EXTRA_CONFIG_PATH = "config_path"
    private const val EXTRA_UPSTREAM_BINARY = "upstream_binary"
    private const val EXTRA_UPSTREAMS_JSON = "upstreams_json"
    private const val EXTRA_UPSTREAM_ARGS = "upstream_args"
    private const val EXTRA_UPSTREAM_PORT = "upstream_port"
    private const val EXTRA_UPSTREAM_LOG = "upstream_log"

    internal fun startIntent(
      context: Context,
      label: String,
      tunAddress: String,
      packages: List<String>,
      binary: String,
      args: List<String>,
      logPath: String,
      configPath: String? = null,
      upstream: UpstreamEngine? = null,
      upstreams: List<UpstreamEngine> = emptyList(),
    ): Intent = Intent(context, VpnEngineService::class.java).apply {
      action = ACTION_START
      putExtra(EXTRA_LABEL, label)
      putExtra(EXTRA_TUN_ADDRESS, tunAddress)
      putExtra(EXTRA_PACKAGES, packages.toTypedArray())
      putExtra(EXTRA_BINARY, binary)
      putExtra(EXTRA_ARGS, args.toTypedArray())
      putExtra(EXTRA_LOG_PATH, logPath)
      configPath?.let { putExtra(EXTRA_CONFIG_PATH, it) }
      upstream?.let { u ->
        putExtra(EXTRA_UPSTREAM_BINARY, u.binary)
        putExtra(EXTRA_UPSTREAM_ARGS, u.args.toTypedArray())
        putExtra(EXTRA_UPSTREAM_PORT, u.port)
        putExtra(EXTRA_UPSTREAM_LOG, u.logPath)
      }
      if (upstreams.isNotEmpty()) {
        putExtra(EXTRA_UPSTREAMS_JSON, JSONArray().apply {
          upstreams.forEach { u -> put(JSONObject().apply {
            put("binary", u.binary); put("args", JSONArray(u.args)); put("port", u.port); put("log", u.logPath); put("env", JSONObject(u.environment))
          }) }
        }.toString())
      }
    }

    fun stopIntent(context: Context): Intent =
      Intent(context, VpnEngineService::class.java).apply { action = ACTION_STOP }

    /**
     * Must be called from the activity before [startIntent] so the system
     * consent flow has somewhere to return to.
     */
    fun prepareIntent(context: Context): Intent? = VpnService.prepare(context)

    /**
     * The socks upstream of a two-process plan (sing-box vpn mode): sing-box is
     * started first, its port is polled, then tun2socks dials it.
     */
    internal data class UpstreamEngine(
      val binary: String,
      val args: List<String>,
      val port: Int,
      val logPath: String,
      val environment: Map<String, String> = emptyMap(),
    )

    internal data class EngineConfig(
      val label: String,
      val tunAddress: String,
      val packages: List<String>,
      val binary: String?,
      val args: List<String>,
      val logPath: String,
      /** Config file that may embed the `%FD%` placeholder (mihomo). */
      val configPath: String? = null,
      /** Optional socks upstream started before the fd engine (sing-box). */
      val upstream: UpstreamEngine? = null,
      val upstreams: List<UpstreamEngine> = emptyList(),
    ) {
      /**
       * Produces the argv tail. The TUN fd is inserted at each occurrence of
       * the [FD_PLACEHOLDER] token, which lets each engine use its own native
       * syntax (`-device fd://N` for tun2socks, `-f config.yaml` for mihomo).
       */
      fun buildArgs(fd: Int): List<String> = args.map { it.replace(FD_PLACEHOLDER, fd.toString()) }

      companion object {
        const val FD_PLACEHOLDER = "%FD%"

        fun fromIntent(intent: Intent): EngineConfig {
          val upstreamBinary = intent.getStringExtra(EXTRA_UPSTREAM_BINARY)
          val upstream = upstreamBinary?.let {
            UpstreamEngine(
              binary = it,
              args = intent.getStringArrayExtra(EXTRA_UPSTREAM_ARGS)?.toList().orEmpty(),
              port = intent.getIntExtra(EXTRA_UPSTREAM_PORT, 0),
              logPath = intent.getStringExtra(EXTRA_UPSTREAM_LOG).orEmpty(),
            )
          }
          val upstreams = runCatching {
            val a = JSONArray(intent.getStringExtra(EXTRA_UPSTREAMS_JSON) ?: "[]")
            (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
              UpstreamEngine(o.getString("binary"), (0 until o.getJSONArray("args").length()).map(o.getJSONArray("args")::getString), o.getInt("port"), o.getString("log"), o.optJSONObject("env")?.let { e -> e.keys().asSequence().associateWith { k -> e.optString(k) } } ?: emptyMap())
            } }
          }.getOrDefault(emptyList())
          return EngineConfig(
            label = intent.getStringExtra(EXTRA_LABEL).orEmpty(),
            tunAddress = intent.getStringExtra(EXTRA_TUN_ADDRESS)
              ?: DEFAULT_TUN_ADDRESS,
            packages = intent.getStringArrayExtra(EXTRA_PACKAGES)?.toList().orEmpty(),
            binary = intent.getStringExtra(EXTRA_BINARY),
            args = intent.getStringArrayExtra(EXTRA_ARGS)?.toList().orEmpty(),
            logPath = intent.getStringExtra(EXTRA_LOG_PATH).orEmpty(),
            configPath = intent.getStringExtra(EXTRA_CONFIG_PATH),
            upstream = upstream,
            upstreams = upstreams,
          )
        }
      }
    }
  }
}
