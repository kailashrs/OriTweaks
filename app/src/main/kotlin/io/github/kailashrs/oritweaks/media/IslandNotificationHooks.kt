package io.github.kailashrs.oritweaks.media

import io.github.kailashrs.oritweaks.core.Features
import io.github.kailashrs.oritweaks.core.Hooks

/**
 * SystemUI hides an app's own media notification (MediaCoordinator's filter) when the control-center
 * music card covers it. On OriginOS 7 the filter asks VivoNotiMixMusicController.inMixMusicWhitelist,
 * which checks supportPkgList, the whitelist the card plugin (com.vivo.musicmixcard) reports. Apps
 * added to the island list get the card too, so they are added to that list on demand to avoid a
 * duplicate player. (OriginOS 6 filled the list from VivoMixMusicPlugin.Callback.mixMusicCallbackToQSCenter.)
 */
object IslandNotificationHooks {
    private const val CONTROLLER = "com.vivo.systemui.plugin.notification.VivoNotiMixMusicController"

    fun install(loader: ClassLoader) {
        val extra = Features.apps(Features.ISLAND_MUSIC)
        if (extra.isEmpty()) return
        val controller = Hooks.findClass(CONTROLLER, loader)
        Hooks.hook(Hooks.method(controller, "inMixMusicWhitelist", Int::class.javaPrimitiveType!!, String::class.java)) { chain ->
            val packageName = chain.args[1] as String?
            if (packageName != null && packageName in extra) {
                @Suppress("UNCHECKED_CAST")
                val whitelist = Hooks.get(chain.thisObject!!, "supportPkgList") as MutableList<String>
                // An empty list means the card is off (or overseas): SystemUI keeps its own answer.
                if (whitelist.isNotEmpty() && packageName !in whitelist) {
                    whitelist.add(packageName)
                    Hooks.info("island music: card whitelist += $packageName")
                }
            }
            chain.proceed()
        }
    }
}
