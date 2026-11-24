package dev.pathpilot.app.runtime

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Protect the complete release sequence, including returns from Main to the owner's IO dispatcher. */
internal suspend fun releaseRouterResources(
    markStopping: suspend () -> Unit,
    stopServer: () -> Unit,
    releaseWifiLock: () -> Unit,
    releaseWakeLock: () -> Unit,
    onReleased: suspend (Exception?) -> Unit,
) = withContext(NonCancellable) {
    markStopping()
    var failure: Exception? = null
    try {
        try {
            stopServer()
        } finally {
            try {
                releaseWifiLock()
            } finally {
                releaseWakeLock()
            }
        }
    } catch (error: Exception) {
        failure = error
    }
    onReleased(failure)
}
