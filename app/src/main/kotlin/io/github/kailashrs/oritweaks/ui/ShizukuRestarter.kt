package io.github.kailashrs.oritweaks.ui

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Restarts scoped apps through Shizuku, so changes apply without the module's system_server receiver
 * (which only exists after a soft reboot). Under root Shizuku this also kills persistent and
 * system-uid processes (GameWatch, GameCube's daemon) that `am force-stop` leaves running.
 */
object ShizukuRestarter {
    private const val TAG = "OriTweaks"

    fun available(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun granted(): Boolean = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    /** Returns false when the command could not be run (no binder, no permission or a failure). */
    fun restart(packages: Collection<String>): Boolean {
        if (!available() || !granted() || packages.isEmpty()) return false
        // Package names are fixed module constants, so they can be inlined into the script.
        val script = packages.joinToString("; ") { pkg ->
            "am force-stop $pkg; for pid in \$(ps -A -o PID=,NAME= | awk -v p=$pkg '\$2==p || index(\$2, p\":\")==1 {print \$1}'); do kill -9 \$pid; done"
        }
        return runCatching {
            // newProcess is hidden in Shizuku API 13 but still served; a UserService would be overkill here.
            val method = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("sh", "-c", script), null, null) as Process
            process.waitFor() == 0
        }.onFailure { Log.e(TAG, "Shizuku restart failed", it) }.getOrDefault(false)
    }
}
