package io.github.kailashrs.oritweaks.media

import android.content.res.Resources
import io.github.kailashrs.oritweaks.core.Features
import io.github.kailashrs.oritweaks.core.Hooks
import java.lang.reflect.Modifier

/**
 * Origin Island's music card is served by com.vivo.musicwidgetmix (island side: SystemUIPlugin's
 * RemoteMusicManager). It only follows apps on its lists: the built-in defaults in a singleton
 * (s4.m0 on OriginOS 6, w6.v0 on OriginOS 7) and the cloud whitelist, into which
 * R.array.framework_support_list is merged for the cooperate, resident and lock-screen lists.
 * Non-cooperating apps such as Spotify already work through its generic MediaController path,
 * so adding a package to these lists is enough. Checked on VivoMusicWidgetMix 6.2.7.2 and 17.0.9.1.
 */
object IslandMusicHooks {
    private const val PACKAGE = "com.vivo.musicwidgetmix"

    /** Obfuscated holder of the built-in lists: s4.m0 on OriginOS 6 (6.2.7.2), w6.v0 on OriginOS 7 (17.0.9.1). */
    private val HOLDERS = listOf("w6.v0", "s4.m0")

    /** Apps every music list contains, which tells them apart from the other lists in the holder. */
    private val ANCHORS = setOf("com.android.bbkmusic", "com.netease.cloudmusic", "com.tencent.qqmusic")

    fun install(loader: ClassLoader) {
        val extra = Features.apps(Features.ISLAND_MUSIC)
        if (extra.isEmpty()) return

        Hooks.hook(Hooks.method(Resources::class.java, "getStringArray", Int::class.javaPrimitiveType!!)) { chain ->
            val result = chain.proceed() as Array<*>
            val resources = chain.thisObject as Resources
            val name = runCatching { resources.getResourceName(chain.args[0] as Int) }.getOrNull()
            if (name != "$PACKAGE:array/framework_support_list") return@hook result
            Hooks.info("island music: framework support list += $extra")
            (result.filterIsInstance<String>() + extra.filter { it !in result }).toTypedArray()
        }

        // utils.d.e(Context, String) is the per-app "version supported" check behind the support map; apps
        // outside its switch pass only if they ship vivo's SDK service. Without it the card shows just the name.
        val appUtils = Hooks.findClass("com.vivo.musicwidgetmix.utils.d", loader)
        Hooks.hook(Hooks.method(appUtils, "e", android.content.Context::class.java, String::class.java)) { chain ->
            if (chain.args[1] in extra) true else chain.proceed()
        }

        // Built-in lists: the singleton fills them in its private constructor. It also holds lists that are
        // not music lists (OriginOS 7: a blacklist of phone, contacts, email, ...), so only lists that
        // already contain well-known music apps are extended.
        val defaults = HOLDERS.firstNotNullOfOrNull { name ->
            Hooks.findClassOrNull(name, loader)?.takeIf { listGetters(it).size >= 3 }
        } ?: error("no music app list holder found among $HOLDERS")
        val getters = listGetters(defaults)
        for (constructor in defaults.declaredConstructors) {
            Hooks.hook(constructor) { chain ->
                val result = chain.proceed()
                for (getter in getters) {
                    @Suppress("UNCHECKED_CAST")
                    val list = getter.invoke(chain.thisObject) as MutableList<Any?>
                    if (ANCHORS.none { it in list }) continue
                    extra.filter { it !in list }.forEach { list.add(it) }
                }
                result
            }
        }
        Hooks.info("island music: added $extra to the lists of ${defaults.name}")
    }

    private fun listGetters(type: Class<*>) = type.declaredMethods.filter {
        Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
            List::class.java.isAssignableFrom(it.returnType)
    }
}
