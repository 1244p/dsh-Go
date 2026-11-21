package io.github.ccch1mneyyy.dsh_app.xposed

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import io.github.libxposed.api.XposedInterface

/**
 * Keeps DSH Go's accessibility service in `ENABLED_ACCESSIBILITY_SERVICES` when ColorOS takes it out.
 *
 * Why this exists: the app already repairs that list itself (`A11yKeeper` in the Flutter half) with
 * `WRITE_SECURE_SETTINGS`, but it can only act *after* the fact and only from inside its own
 * process, so on a ROM that purges the entry on a timer the app and the system write the same string
 * back and forth forever. Acting inside the one place every writer goes through means the entry is
 * never actually dropped.
 *
 * Two targets, because one is not enough:
 *  - `SettingsProvider` — every write ends here, including `adb shell settings put` and the ROM's
 *    own purge. Its class is not on the system_server class loader (it lives in a priv-app APK), so
 *    the class is taken off the provider *instance* at creation time and hooked from there.
 *  - `Settings$NameValueCache#putStringForUser` — the in-process fast path callers use. Kept as a
 *    belt-and-braces layer; it is installed even if the provider has not been created yet.
 *
 * Armed by `Settings.Secure.dsh_go_a11y_guard`, which the app writes when the person toggles 手机操控
 * (see `A11yKeeper.arm`). With that off — or unset, which is the case before the app holds
 * `WRITE_SECURE_SETTINGS` — every write passes through untouched. The module never turns phone
 * control on by itself; it only stops the system from turning it off.
 */
internal object AccessibilityProtection {
    /** The component `A11yKeeper` defends. A string: this process never loads the service class. */
    private const val SERVICE_CLASS = "io.github.ccch1mneyyy.dsh_app.phone.a11y.PhoneA11yService"

    /** Must equal `A11yKeeper.GUARD_KEY`, which is on the other side of a process boundary. */
    const val GUARD_KEY = "dsh_go_a11y_guard"

    private const val LIST_KEY = "enabled_accessibility_services"

    /** `call()` verb that carries a `name`/`value` pair for the secure namespace. */
    private const val PUT_SECURE = "PUT_secure"

    private const val PROVIDER = "com.android.providers.settings.SettingsProvider"

    /** Same tag the module entry point logs under, so one grep finds everything. */
    private const val TAG = "DshGoModule"
    private const val LOG_INFO = 4

    /**
     * Whether the guard is switched on, as last read by [GuardWatcher].
     *
     * Deliberately *not* read inside the hook: `Settings.Secure.getInt` goes back through the very
     * provider whose `call()` we are inside, and re-entering it on the same thread is a deadlock
     * waiting for the right lock. The watcher does the reading and this stays a plain read.
     */
    @Volatile
    private var armed = false

    /** Set once the provider instance has been seen; the provider hooks are installed exactly once. */
    @Volatile
    private var providerHooked = false

    fun install(module: XposedInterface, loader: ClassLoader, journal: HookJournal) {
        val component = Component(module.modulePackage().ifEmpty { "io.github.ccch1mneyyy.dsh_app" }, SERVICE_CLASS)
        hookNameValueCache(module, loader, journal, component)
        hookProviderCreation(module, loader, journal, component)
    }

    /**
     * The in-process fast path. Harmless if the provider hook never lands: a write that reaches this
     * method carries the whole list, so the same rule applies here.
     */
    private fun hookNameValueCache(
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
                if (nameAt < 0 || valueAt >= args.size) return@hook chain.proceed()
                val incoming = args[valueAt] as? String
                val kept = defend(incoming, component)
                if (kept == incoming) return@hook chain.proceed()
                chain.proceed(replace(args, valueAt, kept))
            }
        }
    }

    /**
     * Catches the provider the moment the framework hands it its context.
     *
     * `ContentProvider.attachInfo` is boot classpath, so it is reachable from the system_server
     * loader, and — unlike every other entry point — the receiver is the provider *instance*, which
     * carries the real `Class`. That is the only reliable way in: `SettingsProvider` lives in a
     * priv-app APK whose classes belong to a loader built for that package, not to the one this
     * module is injected into, so `findClassOrNull` on the system_server loader found nothing.
     */
    private fun hookProviderCreation(
        module: XposedInterface,
        loader: ClassLoader,
        journal: HookJournal,
        component: Component,
    ) {
        val base = HookSupport.findClassOrNull(loader, "android.content.ContentProvider")
        val attach = base
            ?.let { HookSupport.findMethods(it) { method -> method.name == "attachInfo" && method.parameterCount == 2 } }
            ?.firstOrNull()
        if (attach == null) {
            journal.missing("provider-attach", "ContentProvider.attachInfo", "method not found")
            return
        }
        journal.hook(module, "provider-attach", "ContentProvider.attachInfo", attach) { chain ->
            try {
                // The context comes out of the arguments: attachInfo is entered *before* the
                // provider stores it, so `provider.context` is still null on this side of proceed.
                adopt(module, journal, component, chain.thisObject as? ContentProvider, chain.args.getOrNull(0) as? Context)
            } catch (_: Throwable) {
                // Best effort: a provider we cannot adopt leaves the module inert, not broken.
            }
            chain.proceed()
        }
    }

    /** Installs the provider hooks once, the first time the settings provider shows up. */
    private fun adopt(
        module: XposedInterface,
        journal: HookJournal,
        component: Component,
        provider: ContentProvider?,
        context: Context?,
    ) {
        if (provider == null || provider.javaClass.name != PROVIDER) return
        synchronized(this) {
            if (providerHooked) return
            providerHooked = true
            try {
                context?.let { GuardWatcher.attach(it) }
            } catch (_: Throwable) {
                // Without a context the guard stays off; writes still pass through untouched.
            }
            val providerClass = provider.javaClass
            hookCall(module, providerClass, journal, component)
            hookValues(module, providerClass, journal, component)
            // Logged because this is the one thing that can silently not happen: if the provider was
            // already attached before this module got control, nothing below is ever hooked.
            module.log(LOG_INFO, TAG, "settings provider adopted; ${journal.summary()}")
        }
    }

    /**
     * `ContentProvider.call` is what `Settings.Secure.putString`, `adb shell settings put` and the
     * system's own writes all end up in. The value arrives inside the `Bundle` under `name`/`value`.
     */
    private fun hookCall(
        module: XposedInterface,
        provider: Class<*>,
        journal: HookJournal,
        component: Component,
    ) {
        val call = HookSupport.findMethods(provider) {
            it.name == "call" && it.parameterCount == 3 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == String::class.java &&
                it.parameterTypes[2] == Bundle::class.java
        }.firstOrNull()
        journal.hook(module, "provider-call", "SettingsProvider.call", call) { chain ->
            val args = chain.args
            val verb = args[0] as? String
            val request = args[2] as? Bundle
            // The setting name travels in the second argument; `extras` carries only the value.
            val name = args[1] as? String
            if (verb != PUT_SECURE || request == null || name != LIST_KEY) {
                return@hook chain.proceed()
            }
            val incoming = request.getString("value")
            val kept = defend(incoming, component)
            if (kept == incoming) return@hook chain.proceed()
            val rewritten = Bundle(request)
            rewritten.putString("value", kept)
            chain.proceed(arrayOf(args[0], args[1], rewritten))
        }
    }

    /** The legacy `insert`/`update` paths carry the same pair in `ContentValues`. */
    private fun hookValues(
        module: XposedInterface,
        provider: Class<*>,
        journal: HookJournal,
        component: Component,
    ) {
        val insert = HookSupport.findMethods(provider) {
            it.name == "insert" && it.parameterCount == 2 &&
                it.parameterTypes[0] == Uri::class.java && it.parameterTypes[1] == ContentValues::class.java
        }.firstOrNull()
        journal.hook(module, "provider-insert", "SettingsProvider.insert", insert) { chain ->
            rewrite(chain, component) { args, values -> arrayOf(args[0], values) }
                ?.let { chain.proceed(it) } ?: chain.proceed()
        }
        val update = HookSupport.findMethods(provider) {
            it.name == "update" && it.parameterCount == 4 &&
                it.parameterTypes[0] == Uri::class.java && it.parameterTypes[1] == ContentValues::class.java
        }.firstOrNull()
        journal.hook(module, "provider-update", "SettingsProvider.update", update) { chain ->
            rewrite(chain, component) { args, values -> args.toMutableList().also { it[1] = values }.toTypedArray() }
                ?.let { chain.proceed(it) } ?: chain.proceed()
        }
    }

    /** Shared body of the `insert`/`update` hooks: null means "leave this write exactly as it was". */
    private fun rewrite(
        chain: XposedInterface.Chain,
        component: Component,
        rebuild: (List<Any?>, ContentValues) -> Array<Any?>,
    ): Array<Any?>? {
        val args = chain.args
        val uri = args[0] as? Uri
        val values = args[1] as? ContentValues
        if (uri?.lastPathSegment != "secure" || values == null) return null
        if (values.getAsString("name") != LIST_KEY) return null
        val incoming = values.getAsString("value")
        val kept = defend(incoming, component)
        if (kept == incoming) return null
        val rewritten = ContentValues(values)
        rewritten.put("value", kept)
        return rebuild(args, rewritten)
    }

    /**
     * [incoming] with our component kept in it, or [incoming] unchanged when the guard is off.
     *
     * An empty value is never replaced: on this ROM a write racing the provider's own load passes
     * `""`, and turning that into "just us" is how the first version of this module wiped the other
     * third-party services off the device. Refusing to act on an empty list loses nothing — the next
     * write carries the real one.
     */
    private fun defend(incoming: String?, component: Component): String? {
        if (!isArmed()) return incoming
        return keep(incoming, component)
    }

    /**
     * Whether the person has phone control switched on.
     *
     * Two sources, because neither alone is enough: the observer-maintained [armed] flag is the fast
     * path and the only one that can be trusted while the provider is half-built, and a direct read
     * covers the case where the observer never registered (it can fail silently on a locked-down
     * ROM) — which is exactly the state the first build shipped in, with a guard that never armed.
     *
     * The direct read re-enters `Settings.Secure.getInt`, whose `call()` our own hook is sitting on,
     * so a per-thread flag keeps that from becoming unbounded recursion. The re-entrant call is a
     * `GET_secure` verb, which the hook passes straight through, so one extra frame is all it is.
     */
    private fun isArmed(): Boolean {
        if (armed) return true
        if (reading.get() == true) return false
        reading.set(true)
        try {
            val resolver = resolver ?: return false
            val on = try {
                Settings.Secure.getInt(resolver, GUARD_KEY, 0) == 1
            } catch (_: Throwable) {
                false
            }
            if (on) armed = true
            return on
        } finally {
            reading.set(false)
        }
    }

    /** Content resolver of the settings provider, captured when the provider was adopted. */
    @Volatile
    private var resolver: android.content.ContentResolver? = null

    /** Per-thread guard so a fallback read cannot recurse into itself. */
    private val reading = ThreadLocal.withInitial { false }

    /**
     * The rule itself, apart from the arm switch so the JVM tests can pin it: an empty or missing
     * value comes back exactly as it arrived, never as "just us".
     */
    internal fun keep(incoming: String?, component: Component): String? =
        AccessibilityList.ensureWhenPresent(incoming, component)

    private fun replace(args: List<Any?>, index: Int, value: Any?): Array<Any?> =
        args.toMutableList().also { it[index] = value }.toTypedArray()

    /**
     * Reads `dsh_go_a11y_guard` off the main thread and keeps [armed] current.
     *
     * Event driven rather than polled: one observer on that single key. The thread is a daemon so it
     * can never hold the boot open.
     */
    private object GuardWatcher {
        @Volatile
        private var started = false

        fun attach(context: Context) {
            if (started) return
            synchronized(this) {
                if (started) return
                started = true
            }
            val resolver = context.contentResolver
            AccessibilityProtection.resolver = resolver
            val thread = HandlerThread("dsh-a11y-guard").apply { isDaemon = true; start() }
            val handler = Handler(thread.looper)
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    armed = read(resolver)
                }
            }
            // Deferred on purpose. This is called from inside `attachInfo`, i.e. while the settings
            // provider is still being built, so reading a setting right here re-enters the very
            // object that is half-constructed and fails — which is how the first version ended up
            // with an arm switch that was never on.
            handler.post {
                armed = read(resolver)
                try {
                    resolver.registerContentObserver(Settings.Secure.getUriFor(GUARD_KEY), false, observer)
                } catch (_: Throwable) {
                    // Without the observer the switch only takes effect on the next process start.
                }
            }
        }

        private fun read(resolver: android.content.ContentResolver): Boolean = try {
            Settings.Secure.getInt(resolver, GUARD_KEY, 0) == 1
        } catch (_: Throwable) {
            false
        }
    }

    /** `applicationId` of this APK, i.e. the app whose service we defend. */
    private fun XposedInterface.modulePackage(): String = try {
        getModuleApplicationInfo().packageName.orEmpty()
    } catch (_: Throwable) {
        ""
    }
}
