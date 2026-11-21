package io.github.ccch1mneyyy.dsh_app.xposed

import android.content.Context
import android.provider.Settings
import io.github.libxposed.api.XposedInterface

/**
 * Keeps DSH Go's accessibility service in `ENABLED_ACCESSIBILITY_SERVICES` when ColorOS takes it out.
 *
 * Why this exists: the app already repairs that list itself (`A11yKeeper` in the Flutter half) with
 * `WRITE_SECURE_SETTINGS`, but it can only act *after* the fact and only from inside its own
 * process, so on a ROM that purges the entry on a timer the app and the system write the same string
 * back and forth forever. Acting inside `system_server`, at the one place the value reaches the
 * provider, means the entry is never actually dropped.
 *
 * Two hooks, both optional on purpose:
 *  - `Settings$NameValueCache#putStringForUser` — rewrite the incoming list so ours survives. The
 *    ROM's own caller still sees its write succeed.
 *  - `SystemServer#startOtherServices` — one pass after the settings provider is up, to repair a
 *    list the ROM rewrote while we were loading.
 *
 * Both are armed by `Settings.Secure.dsh_go_a11y_guard`, which the app writes when the person
 * toggles 手机操控 (see `A11yKeeper.arm`). With that off — or unset, which is the case before the app
 * holds `WRITE_SECURE_SETTINGS` — every write passes through untouched. The module never turns phone
 * control on by itself; it only stops the system from turning it off.
 */
internal object AccessibilityProtection {
    /** The component `A11yKeeper` defends. A string: this process never loads the service class. */
    private const val SERVICE_CLASS = "io.github.ccch1mneyyy.dsh_app.phone.a11y.PhoneA11yService"

    /** Must equal `A11yKeeper.GUARD_KEY`, which is on the other side of a process boundary. */
    const val GUARD_KEY = "dsh_go_a11y_guard"

    private const val LIST_KEY = "enabled_accessibility_services"

    /** The system_server class loader, captured at install time for the reflective lookups below. */
    @Volatile
    private var systemLoader: ClassLoader? = null

    fun install(module: XposedInterface, loader: ClassLoader, journal: HookJournal) {
        systemLoader = loader
        val component = Component(module.modulePackage().ifEmpty { "io.github.ccch1mneyyy.dsh_app" }, SERVICE_CLASS)

        hookSettingsWrites(module, loader, journal, component)
        hookServiceStart(module, loader, journal, component)
    }

    /**
     * The choke point where any caller writes a secure setting. The name and value arrive among
     * positional `String` arguments and the arity has changed across releases, so the body finds
     * them by value rather than by index.
     */
    private fun hookSettingsWrites(
        module: XposedInterface,
        loader: ClassLoader,
        journal: HookJournal,
        component: Component,
    ) {
        // getName() of a nested class is a.b.C$D, hence the escaped $.
        val cache = HookSupport.findClassOrNull(loader, "android.provider.Settings\$NameValueCache")
        if (cache == null) {
            journal.missing("settings-write", "NameValueCache", "class not found")
            return
        }
        val targets = HookSupport.findMethods(cache) { it.name == "putStringForUser" }
        if (targets.isEmpty()) {
            journal.missing("settings-write", "NameValueCache.putStringForUser", "method not found")
            return
        }
        targets.forEachIndexed { index, method ->
            journal.hook(
                module,
                "settings-write-$index",
                "NameValueCache.putStringForUser/${method.parameterCount}",
                method,
            ) { chain ->
                val args = chain.args
                val nameAt = args.indexOfFirst { it is String && it == LIST_KEY }
                val valueAt = nameAt + 1
                if (nameAt < 0 || valueAt >= args.size || !armed()) {
                    return@hook chain.proceed()
                }
                val incoming = args[valueAt] as? String
                val kept = AccessibilityList.ensure(incoming, component)
                if (kept == incoming.orEmpty()) {
                    return@hook chain.proceed()
                }
                chain.proceed(replace(args, valueAt, kept))
            }
        }
    }

    /** After the settings provider exists, repair a list the ROM already rewrote. */
    private fun hookServiceStart(
        module: XposedInterface,
        loader: ClassLoader,
        journal: HookJournal,
        component: Component,
    ) {
        val server = HookSupport.findClassOrNull(loader, "com.android.server.SystemServer")
        // Android 14+ declares `private void startOtherServices(TimingsTraceAndSlog)`. Matching on
        // the parameter type would mean naming a hidden class, so match on arity instead and let a
        // future overload with the same arity surface as a wrong-target hook rather than a silent
        // skip. `findMethods` already makes it accessible, which is what calling a private member
        // from a hook needs.
        val start = server
            ?.let { HookSupport.findMethods(it) { method -> method.name == "startOtherServices" && method.parameterCount == 1 } }
            ?.firstOrNull()
        if (start == null) {
            journal.missing("repair", "SystemServer.startOtherServices", "method not found")
            return
        }
        journal.hook(module, "repair", "SystemServer.startOtherServices", start) { chain ->
            val result = chain.proceed()
            try {
                repair(component)
            } catch (_: Throwable) {
                // Best effort: a broken repair must not take the boot down.
            }
            result
        }
    }

    private fun repair(component: Component) {
        val resolver = systemContext()?.contentResolver ?: return
        if (Settings.Secure.getInt(resolver, GUARD_KEY, 0) != 1) return
        val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val kept = AccessibilityList.ensure(current, component)
        if (kept != current.orEmpty()) {
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, kept)
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }
    }

    /**
     * Read from the provider, not from a cache: the app flips this key and the ROM may have
     * rewritten everything else, so the guard state has to be current on every write.
     */
    private fun armed(): Boolean = try {
        val resolver = systemContext()?.contentResolver
        resolver != null && Settings.Secure.getInt(resolver, GUARD_KEY, 0) == 1
    } catch (_: Throwable) {
        false
    }

    /**
     * `ActivityThread.currentActivityThread().getSystemContext()`, by name. Both are hidden API and
     * absent from the compile SDK; inside system_server there is no hidden-API enforcement, and the
     * catch keeps a ROM that moved them from taking the boot down.
     */
    private fun systemContext(): Context? = try {
        val loader = systemLoader ?: ClassLoader.getSystemClassLoader()
        val threadClass = HookSupport.findClassOrNull(loader, "android.app.ActivityThread") ?: return null
        val thread = HookSupport.findMethod(threadClass, "currentActivityThread")?.invoke(null)
            ?: HookSupport.findMethod(threadClass, "systemMain")?.invoke(null)
            ?: return null
        HookSupport.findMethod(threadClass, "getSystemContext")?.invoke(thread) as? Context
    } catch (_: Throwable) {
        null
    }

    private fun replace(args: List<Any?>, index: Int, value: Any?): Array<Any?> =
        args.toMutableList().also { it[index] = value }.toTypedArray()

    /** `applicationId` of this APK, i.e. the app whose service we defend. */
    private fun XposedInterface.modulePackage(): String = try {
        getModuleApplicationInfo()?.packageName.orEmpty()
    } catch (_: Throwable) {
        ""
    }
}
