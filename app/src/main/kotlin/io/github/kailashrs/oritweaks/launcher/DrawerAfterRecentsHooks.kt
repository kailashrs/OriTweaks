package io.github.kailashrs.oritweaks.launcher

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import io.github.kailashrs.oritweaks.core.Hooks

/**
 * Leaving recents starts RecentsView's exit-to-home guard animator (QuickstepPlugin), which only ends
 * once the launcher's scale spring has fully settled, ~0.5-0.75 s after home is visibly back. Until then
 * Workspace's "checkRecentViewAnimationIfNeeded" (the only boolean method taking a DrawerContainerView)
 * rejects every swipe-up to the app drawer. vivo's own home gesture force-ends that animator
 * (LauncherInputConsumer -> forceEndExitToHomeAnim(recentsView, true)); a drawer swipe now does the same,
 * but with skipToCancel=false so the launcher scale spring jumps to its end (full size) instead of
 * stopping mid-way, which the home gesture can afford only because it animates the launcher itself.
 */
object DrawerAfterRecentsHooks {
    fun install(loader: ClassLoader) {
        val workspace = Hooks.findClass("com.bbk.launcher2.ui.Workspace", loader)
        val drawer = Hooks.findClass("com.bbk.launcher2.ui.allapps.DrawerContainerView", loader)
        val check = workspace.declaredMethods.single {
            it.returnType == Boolean::class.javaPrimitiveType && it.parameterTypes.contentEquals(arrayOf(drawer))
        }
        val hub = Hooks.findClass("com.android.quickstep.recents.plugin.RecentsPluginHub", loader)

        Hooks.hook(check) { chain ->
            val allowed = chain.proceed() as Boolean
            if (allowed) return@hook true
            val panel = overviewPanel((chain.thisObject as View).context) ?: return@hook false
            val plugin = hub.getMethod("getInstance").invoke(null)
                .let { it.javaClass.getMethod("getRecentsPlugin").invoke(it) } ?: return@hook false
            val running = plugin.javaClass.getMethod("isRecentsExitToHomeAnimRunning", View::class.java).invoke(plugin, panel) as Boolean
            if (!running) return@hook false
            plugin.javaClass.getMethod("forceEndExitToHomeAnim", View::class.java, Boolean::class.javaPrimitiveType)
                .invoke(plugin, panel, false)
            val retried = chain.proceed() as Boolean
            Hooks.info("drawer: ended recents exit animation early, allowed=$retried")
            retried
        }
    }

    private fun overviewPanel(context: Context): View? {
        var current: Context? = context
        while (current != null) {
            runCatching { return current.javaClass.getMethod("getOverviewPanel").invoke(current) as View? }
            current = (current as? ContextWrapper)?.baseContext
        }
        return null
    }
}
