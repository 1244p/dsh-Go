package io.github.ccch1mneyyy.dsh_app.xposed

/**
 * The list arithmetic for `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`, apart from Android so
 * the JVM tests can pin it. The setting is a `:`-joined list of `pkg/cls` or `pkg/.Short` entries.
 *
 * Two rules the tests enforce: entries that are not ours come out exactly as they went in, and a
 * list that already names us is returned untouched (the caller compares identity, not equality, to
 * decide whether it needs to do anything).
 */
internal object AccessibilityList {
    /** True when [list] names [component], accepting the abbreviated `pkg/.Cls` form. */
    fun contains(list: String?, component: Component): Boolean =
        list.orEmpty().split(':').any { matches(it, component) }

    /** [list] with [component] appended when absent; the same instance when it is already there. */
    fun ensure(list: String?, component: Component): String {
        if (contains(list, component)) return list.orEmpty()
        val current = list.orEmpty()
        return if (current.isEmpty()) component.entry else "$current:${component.entry}"
    }

    private fun matches(raw: String, component: Component): Boolean {
        val entry = raw.trim()
        val slash = entry.indexOf('/')
        if (slash <= 0) return false
        if (entry.substring(0, slash) != component.packageName) return false
        val cls = entry.substring(slash + 1)
        return cls == component.className ||
            (cls.startsWith('.') && component.packageName + cls == component.className)
    }
}

/** `pkg/cls` for one accessibility service. */
internal data class Component(val packageName: String, val className: String) {
    val entry: String get() = "$packageName/$className"
}
