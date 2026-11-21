package io.github.ccch1mneyyy.dsh_app.phone.background

/**
 * What the fixed device verbs may touch. Pure data and string checks so the JVM tests can pin
 * them; every filesystem or framework call lives in [DeviceVerbs].
 *
 * The engine has no shell verb on purpose: it sits behind a public relay, so a leaked device
 * cookie must not turn into uid 0 arbitrary command execution. Everything reachable from here is
 * one of three bounded surfaces — a curated settings key, a component enabled-state change on a
 * package the person did not deny, and files under shared storage.
 */
object DeviceSurface {
    const val NAMESPACE_SYSTEM = "system"
    const val NAMESPACE_SECURE = "secure"
    const val NAMESPACE_GLOBAL = "global"

    const val OP_READ = "read"
    const val OP_WRITE = "write"
    const val OP_LIST = "list"

    const val APP_DISABLE = "disable"
    const val APP_ENABLE = "enable"
    const val APP_FORCE_STOP = "forceStop"
    const val APP_INFO = "info"

    /**
     * Settings a person may reasonably ask the agent to change, and the value range accepted for
     * each. Deliberately absent: `adb_enabled`, `development_settings_enabled`,
     * `install_non_market_apps`, `mock_location` (a mock location provider would let the agent
     * forge where the device is), `enabled_accessibility_services` and every `*_services` key
     * (writing those would let the agent grant itself or a stranger accessibility powers).
     */
    val SETTING_RANGES: Map<String, Map<String, IntRange>> = linkedMapOf(
        NAMESPACE_SYSTEM to linkedMapOf(
            "screen_brightness" to 0..255,
            "screen_brightness_mode" to 0..1,
            "screen_off_timeout" to 5_000..1_800_000,
            "accelerometer_rotation" to 0..1,
            "user_rotation" to 0..3,
            "sound_effects_enabled" to 0..1,
            "vibrate_when_ringing" to 0..1,
            "haptic_feedback_enabled" to 0..1,
            "show_touches" to 0..1,
            "pointer_location" to 0..1,
        ),
        NAMESPACE_SECURE to linkedMapOf(
            "location_mode" to 0..3,
            "accessibility_display_magnification_enabled" to 0..1,
            "accessibility_display_magnification_scale" to 1..8,
            "accessibility_large_pointer_icon" to 0..1,
            "long_press_timeout" to 300..3_000,
        ),
        NAMESPACE_GLOBAL to linkedMapOf(
            "auto_time" to 0..1,
            "auto_time_zone" to 0..1,
            "stay_on_while_plugged_in" to 0..7,
            "zen_mode" to 0..3,
            "low_battery_sound_timeout" to 0..60_000,
        ),
    )

    fun namespaces(): List<String> = SETTING_RANGES.keys.toList()

    fun settingKeys(namespace: String): List<String> = SETTING_RANGES[namespace]?.keys?.toList().orEmpty()

    /** Null when allowed; otherwise the stable error code the caller turns into a response. */
    fun settingProblem(namespace: String?, key: String?, value: Int?): String? {
        val ranges = SETTING_RANGES[namespace ?: return "deviceKeyNotAllowed"] ?: return "deviceKeyNotAllowed"
        val range = ranges[key ?: return "deviceKeyNotAllowed"] ?: return "deviceKeyNotAllowed"
        if (value != null && value !in range) return "invalidArguments"
        return null
    }

    /**
     * Shared storage is the only tree these verbs reach. `/sdcard` and `/storage/emulated/0` are
     * the same directory; both spellings are accepted and compared after canonicalisation.
     * `Android/data` and `Android/obb` hold other apps' private files, which is the one place a
     * root file tool would otherwise become an app-data exfiltration tool.
     */
    val FILE_ROOTS: List<String> = listOf("/sdcard", "/storage/emulated/0")

    val FORBIDDEN_PREFIXES: List<String> = listOf(
        "/sdcard/Android/data",
        "/sdcard/Android/obb",
        "/sdcard/Android/media",
        "/storage/emulated/0/Android/data",
        "/storage/emulated/0/Android/obb",
        "/storage/emulated/0/Android/media",
    )

    /** Empty for a usable path; a stable error code otherwise. */
    fun pathProblem(path: String?): String? {
        val value = path ?: return "invalidArguments"
        if (value.isEmpty()) return "invalidArguments"
        if (value.contains('\u0000')) return "invalidArguments"
        if (!value.startsWith("/")) return "pathOutsideRoot"
        if (value.split('/').any { it == ".." }) return "pathOutsideRoot"
        // Refuse an outside root on the string alone, before anything touches the filesystem:
        // [DeviceVerbs] canonicalises afterwards, but a request that names /data/data at all has
        // already told us what it is after.
        if (!insideAllowedRoot(value)) return "pathOutsideRoot"
        return null
    }

    /**
     * True when [canonical] (already resolved through symlinks by the caller) is inside shared
     * storage and not inside another app's private tree.
     */
    fun insideAllowedRoot(canonical: String): Boolean {
        val normalized = canonical.trimEnd('/')
        val root = FILE_ROOTS.firstOrNull { normalized == it || normalized.startsWith("$it/") } ?: return false
        if (root.isEmpty()) return false
        if (FORBIDDEN_PREFIXES.any { normalized == it || normalized.startsWith("$it/") }) return false
        return true
    }

    /**
     * Component enabled-state and force-stop are advisory to the person's own deny list: an app
     * on it is never touched, and neither is this app itself.
     */
    fun appProblem(packageName: String?, action: String?, denyPackages: List<String>): String? {
        val pkg = packageName ?: return "invalidArguments"
        if (pkg.isEmpty() || pkg.length > 255) return "invalidArguments"
        if (pkg.split('.').any { it.isEmpty() }) return "devicePackageUnknown"
        if (action !in setOf(APP_DISABLE, APP_ENABLE, APP_FORCE_STOP, APP_INFO)) return "invalidArguments"
        if (pkg == Proto.APP_PACKAGE) return "devicePackageNotAllowed"
        if (pkg in denyPackages) return "devicePackageNotAllowed"
        return null
    }

    /** Single directory level, names ending in `/` for directories; sorted, bounded by [limit]. */
    const val MAX_ENTRIES = 500

    /** A read or write larger than this must be paged: one frame is 8 MiB and also carries the bundle. */
    const val MAX_BYTES = 2 * 1024 * 1024

    fun byteRange(offset: Int, limit: Int, size: Long): Pair<Int, Int>? {
        if (offset < 0 || limit <= 0) return null
        if (offset.toLong() > size) return null
        val capped = minOf(limit, MAX_BYTES)
        val end = minOf(size, offset.toLong() + capped)
        if (end <= offset) return (offset to offset)
        return offset to end.toInt()
    }
}
