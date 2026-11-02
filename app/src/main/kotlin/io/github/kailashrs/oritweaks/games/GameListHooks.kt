package io.github.kailashrs.oritweaks.games

import android.app.Application
import io.github.kailashrs.oritweaks.core.Features
import io.github.kailashrs.oritweaks.core.Hooks
import java.io.File
import java.io.FileInputStream

/**
 * GameCube (UI, imports into its database when the XML version is newer) and GameWatch (service) read
 * the per-device game lists with new FileInputStream("/system/etc/gamecube/<list>.xml"). In those two
 * processes the module hands out a copy from the app's own cache with the chosen games appended, using
 * vivo's generic entries (super resolution state="1"; frame interpolation index="10000", 60 -> 120 fps),
 * and a version raised past the stock one (plus the settings revision) so GameCube re-imports it.
 */
object GameListHooks {
    private enum class Kind(val file: String, val feature: String) {
        SUPER_RESOLUTION("SuperResolutionConfigList.xml", Features.GAME_SUPER_RESOLUTION),
        FRAME_INTERPOLATION("FrameInterConfigList.xml", Features.GAME_FRAME_INTERPOLATION),
    }

    private const val DIR = "/system/etc/gamecube/"
    private const val VERSION_OFFSET = 1000

    fun install(cacheDir: File) {
        val kinds = Kind.entries.associateBy { DIR + it.file }
        Hooks.hook(FileInputStream::class.java.getDeclaredConstructor(String::class.java)) { chain ->
            val kind = kinds[chain.args[0] as? String] ?: return@hook chain.proceed()
            val merged = runCatching { merged(kind, cacheDir) }
                .onFailure { Hooks.error("games: ${kind.file} kept stock", it) }
                .getOrNull() ?: return@hook chain.proceed()
            chain.proceed(arrayOf<Any?>(merged.path))
        }
    }

    private fun merged(kind: Kind, cacheDir: File): File? {
        val stock = File(DIR + kind.file).readText()
        val version = Regex("<version>(\\d+)</version>").find(stock)?.groupValues?.get(1)?.toInt() ?: return null
        val listed = Regex("pkgName=\"([^\"]+)\"").findAll(stock).map { it.groupValues[1] }.toSet()
        val added = Features.apps(kind.feature).filter { it !in listed }.sorted()
        val entries = added.joinToString("") { entry(kind, it, label(it)) }
        val text = stock
            .replaceFirst("<version>$version</version>", "<version>${version + VERSION_OFFSET + Features.listRevision()}</version>")
            .replace("</games>", "$entries</games>")
        val out = File(cacheDir, "oritweaks-${kind.file}")
        if (!out.exists() || out.readText() != text) {
            out.parentFile?.mkdirs()
            out.writeText(text)
            Hooks.info("games: ${kind.file} +$added")
        }
        return out
    }

    private fun entry(kind: Kind, pkg: String, label: String): String = when (kind) {
        Kind.SUPER_RESOLUTION ->
            "    <item pkgName=\"$pkg\" label=\"$label\" channel=\"#\" state=\"1\" frameDepress=\"\" frameInter=\"\"  monsterFrameDepress=\"\" monsterFrameInter=\"\"/>\n"
        Kind.FRAME_INTERPOLATION -> """
    <game pkgName="$pkg" label="$label" channel="#" index="10000" supportSDK="false">
        <boostFrameValues>
            <item gameFps="60" depressValue="60" frameInterValue="120"/>
        </boostFrameValues>
        <frameRateTips>
            <item gameFps="60" tipStringName="zhen_60" tipString="60帧"/>
        </frameRateTips>
    </game>
"""
    }

    private fun label(pkg: String): String {
        val app = runCatching {
            Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Application?
        }.getOrNull()
        val label = app?.let { runCatching { it.packageManager.getApplicationLabel(it.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull() }
        return (label ?: pkg).replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
