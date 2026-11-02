package io.github.kailashrs.oritweaks.system

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.Process
import io.github.kailashrs.oritweaks.core.Control
import io.github.kailashrs.oritweaks.core.Hooks

/**
 * Executes the settings app's Apply actions from system_server, so the app needs no root grant.
 * Only senders holding the module's signature permission can reach it.
 */
object ControlReceiver {
    /** Only packages this module scopes may be restarted. */
    private val restartable = setOf("com.bbk.launcher2", "com.vivo.musicwidgetmix", "com.android.systemui", "com.vivo.gamecube", "com.vivo.gamewatch")

    fun install(loader: ClassLoader) {
        val ams = Hooks.findClass("com.android.server.am.ActivityManagerService", loader)
        Hooks.hookAll(ams, "systemReady") { chain ->
            val result = chain.proceed()
            runCatching { register(Hooks.get(chain.thisObject, "mContext") as Context) }
                .onFailure { Hooks.error("control receiver registration failed", it) }
            result
        }
    }

    private fun register(context: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                val command = intent.getStringExtra(Control.EXTRA_COMMAND)
                Hooks.info("control: $command")
                when (command) {
                    Control.RESTART -> killProcesses(context, intent.getStringArrayExtra(Control.EXTRA_PROCESSES).orEmpty().filter { it in restartable }.toSet())
                    Control.SOFT_REBOOT -> Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 500)
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(Control.ACTION), Control.PERMISSION, null, Context.RECEIVER_EXPORTED)
        Hooks.info("control receiver registered")
    }

    private fun killProcesses(context: Context, names: Set<String>) {
        val manager = context.getSystemService(ActivityManager::class.java)
        manager.runningAppProcesses.orEmpty()
            .filter { process -> names.any { process.processName == it || process.processName.startsWith("$it:") } }
            .forEach { Process.killProcess(it.pid) }
    }
}
