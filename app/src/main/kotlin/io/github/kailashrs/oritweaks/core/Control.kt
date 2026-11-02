package io.github.kailashrs.oritweaks.core

/** Broadcast contract between the settings app and the system_server part of the module. */
object Control {
    const val ACTION = "io.github.kailashrs.oritweaks.CONTROL"
    const val PERMISSION = "io.github.kailashrs.oritweaks.permission.CONTROL"
    const val EXTRA_COMMAND = "command"
    const val EXTRA_PROCESSES = "processes"

    /** Kills the packages in [EXTRA_PROCESSES] (and their sub-processes) so they restart with new settings. */
    const val RESTART = "restart"
    const val SOFT_REBOOT = "soft_reboot"
}
