package io.github.kailashrs.oritweaks.ui

import android.content.Context
import android.content.Intent
import io.github.kailashrs.oritweaks.core.Control

/** Asks the module's system_server part to act; no root grant is needed by this app. */
object ControlActions {

    fun restart(context: Context, packages: Collection<String>) =
        send(context, Control.RESTART) { putExtra(Control.EXTRA_PROCESSES, packages.toTypedArray()) }

    fun softReboot(context: Context) = send(context, Control.SOFT_REBOOT)

    private fun send(context: Context, command: String, extras: Intent.() -> Unit = {}) {
        context.sendBroadcast(
            Intent(Control.ACTION).setPackage("android").putExtra(Control.EXTRA_COMMAND, command).apply(extras),
        )
    }
}
