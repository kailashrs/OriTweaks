package io.github.kailashrs.oritweaks.core

import android.content.SharedPreferences

/** Feature switches stored in LSPosed remote preferences, readable from every scoped process. */
object Features {
    const val GROUP = "oritweaks"

    const val DRAWER_AFTER_RECENTS = "drawer_after_recents"
    const val ISLAND_MUSIC = "island_music"
    const val GAME_SUPER_RESOLUTION = "game_super_resolution"
    const val GAME_FRAME_INTERPOLATION = "game_frame_interpolation"
    const val CAMERA_FULL_RES = "camera_full_res"

    /** Settings-screen order; the second value is the default. */
    val ALL = linkedMapOf(
        DRAWER_AFTER_RECENTS to true,
        ISLAND_MUSIC to true,
        GAME_SUPER_RESOLUTION to true,
        GAME_FRAME_INTERPOLATION to true,
        CAMERA_FULL_RES to true,
    )

    /** Third-party camera apps the camera hooks load into (the module scope lists them too). */
    val CAMERA_APPS = setOf(
        "com.particlesdevs.photoncamera",
        "net.sourceforge.opencamera",
    )

    /** App lists chosen in the settings app, keyed by their feature; the value is the default selection. */
    val APP_LISTS = mapOf(
        ISLAND_MUSIC to setOf("com.google.android.apps.youtube.music"),
        GAME_SUPER_RESOLUTION to emptySet(),
        GAME_FRAME_INTERPOLATION to emptySet(),
    )

    /** Bumped on every app-list change so cached copies (GameCube's database) get refreshed. */
    const val LIST_REVISION = "list_revision"

    fun appsKey(feature: String) = "${feature}_apps"

    @Volatile
    private var preferences: SharedPreferences? = null

    fun attach(value: SharedPreferences?) {
        preferences = value
    }

    fun enabled(key: String): Boolean {
        val default = ALL[key] ?: false
        return runCatching { preferences?.getBoolean(key, default) }.getOrNull() ?: default
    }

    /** Apps chosen for [feature], or none when the feature is off. */
    fun apps(feature: String): Set<String> {
        if (!enabled(feature)) return emptySet()
        val default = APP_LISTS[feature].orEmpty()
        return runCatching { preferences?.getStringSet(appsKey(feature), default) }.getOrNull() ?: default
    }

    fun listRevision(): Int = runCatching { preferences?.getInt(LIST_REVISION, 0) }.getOrNull() ?: 0
}
