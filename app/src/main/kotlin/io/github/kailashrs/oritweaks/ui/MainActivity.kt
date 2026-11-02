package io.github.kailashrs.oritweaks.ui

import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.google.android.material.R as MaterialR
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.divider.MaterialDivider
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.kailashrs.oritweaks.R
import io.github.kailashrs.oritweaks.core.Features
import io.github.libxposed.service.XposedService
import rikka.shizuku.Shizuku
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var preferences: SharedPreferences? = null
    private val switches = mutableMapOf<String, MaterialSwitch>()
    private val appLabels = mutableMapOf<String, TextView>()

    /** Packages to restart (or [SYSTEM] for a soft reboot) for the changes made so far. */
    private val pending = mutableSetOf<String>()
    private lateinit var applyButton: ExtendedFloatingActionButton

    private class Entry(
        val title: String,
        val summary: String,
        val icon: Int,
        /** What has to restart for a change to apply: package names, or [SYSTEM]. */
        val restart: Set<String>,
        val experimental: Boolean = false,
        /** Shows an app picker for [Features.appsKey] when set; true limits it to games. */
        val pickGames: Boolean? = null,
    )

    private class Section(val title: String, val entries: Map<String, Entry>)

    private val sections = listOf(
        Section("Launcher", linkedMapOf(
            Features.DRAWER_AFTER_RECENTS to Entry("Instant app drawer after recents",
                "Swiping up right after leaving recents opens the app drawer instead of being ignored for about half a second.",
                R.drawable.ic_feature_feed, setOf(LAUNCHER)),
        )),
        Section("Origin Island", linkedMapOf(
            Features.ISLAND_MUSIC to Entry("More music player apps",
                "Shows the island music player for the apps you choose, such as YouTube Music, and hides their own player notification under it.",
                R.drawable.ic_feature_feed, setOf(MUSIC_WIDGET, SYSTEM_UI), pickGames = false),
        )),
        Section("Games", linkedMapOf(
            Features.GAME_SUPER_RESOLUTION to Entry("More super resolution games",
                "Adds the games you choose to the game super resolution list (vivo's generic profile). Games must also be added in Game space.",
                R.drawable.ic_feature_feed, GAME_APPS, experimental = true, pickGames = true),
            Features.GAME_FRAME_INTERPOLATION to Entry("More frame interpolation games",
                "Adds the games you choose to the frame interpolation list with vivo's generic 60 → 120 fps profile. Games must also be added in Game space.",
                R.drawable.ic_feature_feed, GAME_APPS, experimental = true, pickGames = true),
        )),
        Section("Camera", linkedMapOf(
            Features.CAMERA_FULL_RES to Entry("Full resolution in camera apps",
                "Lets PhotonCamera and Open Camera shoot 50 MP on the main, ultrawide and periscope cameras by fixing how they switch the sensor to full resolution. Needs the CamX full-size module.",
                R.drawable.ic_feature_feed, Features.CAMERA_APPS, experimental = true),
        )),
    )

    private val entryOf: Map<String, Entry> = sections.flatMap { it.entries.entries }.associate { it.key to it.value }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))

        val container = findViewById<LinearLayout>(R.id.sections)
        for (section in sections) container.addView(buildSection(container, section))

        applyButton = findViewById(R.id.apply)
        applyButton.setOnClickListener { apply() }
        val margin = resources.getDimensionPixelSize(R.dimen.fab_margin)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.scroll)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            applyButton.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = bars.bottom + margin
                rightMargin = bars.right + margin
            }
            insets
        }

        showStatus(active = null, detail = null)
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                val prefs = runCatching { service.getRemotePreferences(Features.GROUP) }.getOrNull()
                runOnUiThread { bind(prefs, "${service.frameworkName} ${service.frameworkVersion} · API ${service.apiVersion}") }
            }

            override fun onServiceDied(service: XposedService) {
                runOnUiThread { bind(null, null) }
            }
        })
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun buildSection(parent: LinearLayout, section: Section): View {
        val view = layoutInflater.inflate(R.layout.item_section, parent, false)
        view.findViewById<TextView>(R.id.section_title).text = section.title
        val rows = view.findViewById<LinearLayout>(R.id.section_rows)
        section.entries.entries.forEachIndexed { index, (key, entry) ->
            if (index > 0) rows.addView(MaterialDivider(this).apply {
                dividerInsetStart = resources.getDimensionPixelSize(R.dimen.divider_inset)
                dividerInsetEnd = resources.getDimensionPixelSize(R.dimen.fab_margin)
            })
            rows.addView(buildRow(rows, key, entry))
        }
        return view
    }

    private fun buildRow(parent: LinearLayout, key: String, entry: Entry): View {
        val row = layoutInflater.inflate(R.layout.item_feature, parent, false)
        row.findViewById<ImageView>(R.id.feature_icon).setImageResource(entry.icon)
        row.findViewById<TextView>(R.id.feature_title).text = entry.title
        row.findViewById<TextView>(R.id.feature_summary).text = entry.summary
        row.findViewById<Chip>(R.id.feature_experimental).visibility = if (entry.experimental) View.VISIBLE else View.GONE
        val toggle = row.findViewById<MaterialSwitch>(R.id.feature_switch)
        toggle.setOnCheckedChangeListener { button, checked -> if (button.isPressed) save(key, checked) }
        row.setOnClickListener {
            if (toggle.isEnabled) {
                toggle.toggle()
                save(key, toggle.isChecked)
            }
        }
        switches[key] = toggle
        val apps = row.findViewById<TextView>(R.id.feature_apps)
        if (entry.pickGames != null) {
            apps.visibility = View.VISIBLE
            appLabels[key] = apps
            apps.setOnClickListener { if (preferences != null) pickApps(key, entry) }
        }
        return row
    }

    private fun showStatus(active: Boolean?, detail: String?) {
        val card = findViewById<MaterialCardView>(R.id.status_card)
        val (containerAttr, onContainerAttr) = when (active) {
            true -> MaterialR.attr.colorPrimaryContainer to MaterialR.attr.colorOnPrimaryContainer
            false -> MaterialR.attr.colorErrorContainer to MaterialR.attr.colorOnErrorContainer
            null -> MaterialR.attr.colorSurfaceContainerHighest to MaterialR.attr.colorOnSurfaceVariant
        }
        val onContainer = MaterialColors.getColor(card, onContainerAttr)
        card.setCardBackgroundColor(MaterialColors.getColor(card, containerAttr))
        findViewById<ImageView>(R.id.status_icon).apply {
            setImageResource(if (active == false) R.drawable.ic_status_off else R.drawable.ic_status_ok)
            imageTintList = ColorStateList.valueOf(onContainer)
        }
        findViewById<TextView>(R.id.status_title).apply {
            setTextColor(onContainer)
            setText(when (active) {
                true -> R.string.status_active
                false -> R.string.status_inactive
                null -> R.string.status_connecting
            })
        }
        findViewById<TextView>(R.id.status_detail).apply {
            setTextColor(onContainer)
            text = when (active) {
                true -> detail
                false -> getString(R.string.status_inactive_detail)
                null -> getString(R.string.status_connecting_detail)
            }
        }
    }

    private fun bind(prefs: SharedPreferences?, framework: String?) {
        preferences = prefs
        showStatus(active = prefs != null, detail = framework)
        for ((key, toggle) in switches) {
            toggle.isEnabled = prefs != null
            toggle.isChecked = prefs?.getBoolean(key, Features.ALL[key] ?: false) ?: (Features.ALL[key] ?: false)
        }
        for (key in appLabels.keys) showApps(key, chosenApps(key))
    }

    private fun chosenApps(key: String): Set<String> {
        val default = Features.APP_LISTS[key].orEmpty()
        return preferences?.getStringSet(Features.appsKey(key), default) ?: default
    }

    private fun showApps(key: String, packages: Set<String>) {
        val names = packages.map { label(it) ?: it }.sorted()
        appLabels[key]?.text = if (names.isEmpty()) "Choose apps…" else "Apps: ${names.joinToString()} · change…"
    }

    private fun label(pkg: String): String? = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()

    /** Launchable apps; for games, those Android classifies as games are listed first. */
    private fun pickApps(key: String, entry: Entry) {
        val chosen = chosenApps(key)
        val launchable = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.ResolveInfoFlags.of(0),
        ).map { it.activityInfo.applicationInfo }.distinctBy { it.packageName }.filter { it.packageName != packageName }
        val sorted = launchable.sortedWith(compareBy(
            { it.packageName !in chosen },
            { entry.pickGames == true && it.category != android.content.pm.ApplicationInfo.CATEGORY_GAME },
            { packageManager.getApplicationLabel(it).toString().lowercase() },
        ))
        val labels = sorted.map { "${packageManager.getApplicationLabel(it)}\n${it.packageName}" }.toTypedArray()
        val checked = BooleanArray(sorted.size) { sorted[it].packageName in chosen }
        MaterialAlertDialogBuilder(this)
            .setTitle(entry.title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val selected = sorted.filterIndexed { index, _ -> checked[index] }.map { it.packageName }.toSet()
                saveApps(key, selected)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveApps(key: String, packages: Set<String>) {
        val prefs = preferences ?: return
        showApps(key, packages)
        entryOf[key]?.let { pending += it.restart }
        updateApplyButton()
        worker.execute {
            prefs.edit()
                // A plain java.util set: Vector's daemon deserializes it and has no Kotlin classes (EmptySet).
                .putStringSet(Features.appsKey(key), HashSet(packages))
                .putInt(Features.LIST_REVISION, prefs.getInt(Features.LIST_REVISION, 0) + 1)
                .commit()
        }
    }

    private fun save(key: String, enabled: Boolean) {
        val prefs = preferences ?: return
        entryOf[key]?.let { pending += it.restart }
        updateApplyButton()
        worker.execute {
            val editor = prefs.edit().putBoolean(key, enabled)
            // Cached copies (GameCube's database) only refresh when the list revision changes.
            if (key in Features.APP_LISTS) editor.putInt(Features.LIST_REVISION, prefs.getInt(Features.LIST_REVISION, 0) + 1)
            val ok = editor.commit()
            if (!ok) runOnUiThread {
                showStatus(active = false, detail = null)
                findViewById<TextView>(R.id.status_title).setText(R.string.status_save_failed)
            }
        }
    }

    private fun updateApplyButton() {
        if (pending.isEmpty()) {
            applyButton.hide()
            return
        }
        applyButton.setText(if (SYSTEM in pending) R.string.apply_soft_reboot else R.string.apply_restart)
        applyButton.show()
    }

    private fun apply() {
        if (SYSTEM !in pending) {
            restartApps(pending.toSet())
            pending.clear()
            updateApplyButton()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.soft_reboot_title)
            .setMessage(R.string.soft_reboot_message)
            .setPositiveButton(R.string.soft_reboot_confirm) { _, _ -> worker.execute { ControlActions.softReboot(this) } }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Prefers Shizuku (works before the first soft reboot), else the module's system_server receiver. */
    private fun restartApps(packages: Set<String>) {
        if (ShizukuRestarter.available() && !ShizukuRestarter.granted()) {
            Shizuku.addRequestPermissionResultListener(object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    Shizuku.removeRequestPermissionResultListener(this)
                    worker.execute { restartNow(packages) }
                }
            })
            Shizuku.requestPermission(SHIZUKU_REQUEST)
            return
        }
        worker.execute { restartNow(packages) }
    }

    private fun restartNow(packages: Set<String>) {
        if (!ShizukuRestarter.restart(packages)) ControlActions.restart(this, packages)
    }

    private companion object {
        const val SHIZUKU_REQUEST = 1
        const val SYSTEM = "system"
        const val LAUNCHER = "com.bbk.launcher2"
        const val MUSIC_WIDGET = "com.vivo.musicwidgetmix"
        const val SYSTEM_UI = "com.android.systemui"
        val GAME_APPS = setOf("com.vivo.gamecube", "com.vivo.gamewatch")
    }
}
