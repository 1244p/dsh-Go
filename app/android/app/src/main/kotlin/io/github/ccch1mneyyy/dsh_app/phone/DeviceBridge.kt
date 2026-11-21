package io.github.ccch1mneyyy.dsh_app.phone

/**
 * A device verb stopped with a stable reason code. The code is a key of
 * [io.github.ccch1mneyyy.dsh_app.phone.background.Reasons], so the core turns it into the same
 * sentence the 不打扰模式 surface uses.
 */
class DeviceFailure(val code: String) : Exception(code)

/**
 * The privileged device surface, as much of it as the core is allowed to reach.
 *
 * Kept free of Android types on purpose: [PhoneCore] runs against fakes in the JVM tests, and the
 * Android side ([io.github.ccch1mneyyy.dsh_app.phone.background.BackgroundControl]) is the only
 * implementation that talks to the engine. Every method here maps to one fixed protocol verb; there
 * is no shell verb, and no caller string is ever concatenated into a command.
 */
interface DeviceBridge {
    /** The 不打扰模式 mode this channel came from (`shizuku` / `sui` / `root` / `adb`). */
    fun mode(): String

    /** One `settings` value, or null when the key is unset. */
    fun setting(namespace: String?, key: String?): String?

    /** Writes one allow-listed `settings` value and verifies the read-back. */
    fun writeSetting(namespace: String?, key: String?, value: Int?)

    /** Disables, enables, force-stops or describes one app. */
    fun app(packageName: String?, action: String?, denyPackages: List<String>): Map<String, Any?>

    /** Lists one shared-storage directory. */
    fun list(path: String?, limit: Int?): Map<String, Any?>

    /** Reads up to the frame budget starting at [offset]. */
    fun read(path: String?, offset: Int?, limit: Int?): Map<String, Any?>

    /** Writes [data] at [offset], atomically replacing the target file. */
    fun write(path: String?, data: ByteArray?, offset: Int?): Map<String, Any?>

    /** The background status block, for `device_status`. */
    fun status(): Map<String, Any?>
}
