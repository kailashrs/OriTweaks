package io.github.kailashrs.oritweaks

import io.github.kailashrs.oritweaks.camera.CameraHooks
import io.github.kailashrs.oritweaks.core.Features
import io.github.kailashrs.oritweaks.core.Hooks
import io.github.kailashrs.oritweaks.games.GameListHooks
import io.github.kailashrs.oritweaks.launcher.DrawerAfterRecentsHooks
import io.github.kailashrs.oritweaks.media.IslandMusicHooks
import io.github.kailashrs.oritweaks.media.IslandNotificationHooks
import io.github.kailashrs.oritweaks.system.ControlReceiver
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

class OriTweaksModule : XposedModule() {
    private val readyPackages = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        Hooks.attach(this)
        Features.attach(runCatching { getRemotePreferences(Features.GROUP) }.getOrNull())
        Hooks.info("OriTweaks ${BuildConfig.VERSION_NAME} loaded in ${param.processName} ($frameworkName API $apiVersion)")
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        if (!isOriginOs()) return
        Hooks.logFile = java.io.File("/data/system/oritweaks.log")
        Hooks.install("control receiver") { ControlReceiver.install(param.classLoader) }
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        // OriginOS processes can load another package before their own (isFirstPackage is false
        // for com.bbk.launcher2), so de-duplicate by name instead.
        if (!readyPackages.add(param.packageName) || !isOriginOs()) return
        if (Hooks.logFile == null) Hooks.logFile = java.io.File(param.applicationInfo.dataDir, "cache/oritweaks.log")
        Hooks.info("package ready: ${param.packageName} (first=${param.isFirstPackage})")
        when (param.packageName) {
            "com.bbk.launcher2" -> if (Features.enabled(Features.DRAWER_AFTER_RECENTS)) {
                Hooks.install("launcher drawer after recents") { DrawerAfterRecentsHooks.install(param.classLoader) }
            }
            "com.vivo.musicwidgetmix" -> Hooks.install("island music apps") { IslandMusicHooks.install(param.classLoader) }
            "com.android.systemui" -> Hooks.install("island music notifications") { IslandNotificationHooks.install(param.classLoader) }
            // Always installed so switching a list off also re-imports the stock list (version is bumped).
            "com.vivo.gamecube", "com.vivo.gamewatch" ->
                Hooks.install("game lists") { GameListHooks.install(java.io.File(param.applicationInfo.dataDir, "cache")) }
            in Features.CAMERA_APPS -> if (Features.enabled(Features.CAMERA_FULL_RES)) {
                Hooks.install("camera full resolution") { CameraHooks.install(param.packageName) }
            }
        }
    }

    /** Hooks are bound to OriginOS 6/7 class layouts (checked on 7.0, launcher 17.0.0.381); do nothing elsewhere. */
    private fun isOriginOs(): Boolean = runCatching {
        val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        (get.invoke(null, "ro.vivo.os.build.display.id") as String).startsWith("OriginOS")
    }.getOrDefault(false)
}
