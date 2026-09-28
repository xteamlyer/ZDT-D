package com.android.zdtd.service.noroot

import com.android.zdtd.service.api.ApiModels

/**
 * Builds a [ApiModels.StatusReport] for non-root mode.
 *
 * The whole UI (Home, Quick Settings tile, widgets) consumes `StatusReport`, so
 * instead of teaching every screen about a second mode we synthesize the same
 * report locally. This keeps non-root mode indistinguishable from root mode at
 * the UI level, which is what keeps the existing screens working unchanged.
 */
object NonRootStatus {

  /**
   * @param running whether the tunnel is currently up
   * @param lastError optional human readable error from the last start attempt
   */
  fun report(running: Boolean, lastError: String = ""): ApiModels.StatusReport {
    return ApiModels.StatusReport(
      uiRunning = running,
      uiState = if (running) "on" else "off",
      actualRuntimeState = if (running) "on" else "off",
      runtimeState = if (running) "on" else "off",
      startInProgress = false,
      stopInProgress = false,
      servicesPartial = false,
      daemonPid = 0,
      statusUpdatedAtUnix = System.currentTimeMillis() / 1000L,
      lastError = lastError,
    )
  }
}
