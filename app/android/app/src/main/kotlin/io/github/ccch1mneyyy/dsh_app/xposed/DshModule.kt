package io.github.ccch1mneyyy.dsh_app.xposed

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * The LSPosed entry point named by `META-INF/xposed/java_init.list`.
 *
 * This is the same APK used as an ordinary app: the module metadata only takes effect when the
 * person installs it as a module in LSPosed (API 102, i.e. LSPosed 2.0+ / Vector). Without the
 * framework the class is never loaded and the app behaves exactly as before — which is the point:
 * phone control via accessibility has to work without Root *or* LSPosed, and LSPosed only removes
 * the part where ColorOS keeps deleting our accessibility service behind our back.
 *
 * Nothing here throws out into the framework. A module that fails while `system_server` boots
 * would take the phone down with it, so every lookup reports MISSING in [HookJournal] instead.
 */
class DshModule : XposedModule() {
    private var installed = false

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        log(
            LOG_INFO,
            TAG,
            "loaded into ${param.processName} (system_server=${param.isSystemServer}, " +
                "framework=$frameworkName $frameworkVersion, api=$apiVersion)",
        )
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        // system_server is the only process we ask for in scope.list, and the only place where
        // the accessibility list can be put back before ColorOS's own policy looks at it.
        if (installed) return
        installed = true
        val journal = HookJournal(GROUP)
        try {
            AccessibilityProtection.install(this, param.classLoader, journal)
        } catch (error: Throwable) {
            journal.failed("install", "accessibility protection", error)
        }
        quietLog(LOG_INFO, TAG, journal.summary())
    }

    /** [XposedInterface.log], but a framework that dropped it cannot break the module. */
    private fun quietLog(level: Int, tag: String, message: String) {
        try {
            log(level, tag, message)
        } catch (_: Throwable) {
            // Logging is a convenience; the module's job is elsewhere.
        }
    }

    companion object {
        const val TAG = "DshGoModule"
        const val GROUP = "accessibility"

        private const val LOG_INFO = 4
    }
}
