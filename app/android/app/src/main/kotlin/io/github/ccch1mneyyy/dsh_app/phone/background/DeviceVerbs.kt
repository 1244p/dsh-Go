package io.github.ccch1mneyyy.dsh_app.phone.background

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * The three fixed device verbs the privileged engine serves: a curated settings key, a component
 * enabled-state change on an allowed package, and files under shared storage.
 *
 * Runs inside [PrivilegedDisplayCore], so it holds whatever identity the transport gave it — root
 * through [RootStarter], shell through Shizuku/Sui, shell through self-ADB. [Context] is the
 * privileged context, never the app's own.
 *
 * Every entry point throws [DeviceFault] and lets the core map it to [Proto.ERROR].
 */
internal class DeviceVerbs(private val context: Context) {

    internal class DeviceFault(val code: String) : Exception()

    private fun fault(code: String): Nothing = throw DeviceFault(code)

    private val resolver get() = context.contentResolver
    private val packages: PackageManager get() = context.packageManager

    // ---- settings ---------------------------------------------------------------------

    fun readSetting(namespace: String?, key: String?): String? {
        val ns = namespace ?: fault("invalidArguments")
        DeviceSurface.settingProblem(ns, key, null)?.let { fault(it) }
        return when (ns) {
            DeviceSurface.NAMESPACE_SYSTEM -> Settings.System.getString(resolver, key)
            DeviceSurface.NAMESPACE_SECURE -> Settings.Secure.getString(resolver, key)
            else -> Settings.Global.getString(resolver, key)
        }
    }

    /**
     * Writes, then reads the value back. A write the platform silently normalised (brightness
     * clamped, a timeout rounded) is reported as [readbackMismatch] rather than success, because
     * the caller's next decision depends on the device really being in that state.
     */
    fun writeSetting(namespace: String?, key: String?, value: Int?) {
        val ns = namespace ?: fault("invalidArguments")
        val v = value ?: fault("invalidArguments")
        DeviceSurface.settingProblem(ns, key, v)?.let { fault(it) }
        val k = key!!
        val ok = when (ns) {
            DeviceSurface.NAMESPACE_SYSTEM -> Settings.System.putInt(resolver, k, v)
            DeviceSurface.NAMESPACE_SECURE -> Settings.Secure.putInt(resolver, k, v)
            else -> Settings.Global.putInt(resolver, k, v)
        }
        if (!ok) fault("systemOperationDenied")
        if (readSetting(ns, k)?.toIntOrNull() != v) fault("readbackMismatch")
    }

    // ---- apps -------------------------------------------------------------------------

    /** Returns the facts a caller needs to report; throws for a denied or unknown package. */
    fun app(packageName: String?, action: String?, denyPackages: List<String>): Map<String, Any?> {
        DeviceSurface.appProblem(packageName, action, denyPackages)?.let { fault(it) }
        val pkg = packageName!!
        val info = try {
            packages.getApplicationInfo(pkg, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            fault("devicePackageUnknown")
        }
        val enabled = info.enabled
        when (action) {
            DeviceSurface.APP_DISABLE -> setEnabled(pkg, false)
            DeviceSurface.APP_ENABLE -> setEnabled(pkg, true)
            DeviceSurface.APP_FORCE_STOP -> forceStop(pkg)
        }
        return linkedMapOf(
            "package" to pkg,
            "label" to runCatching { packages.getApplicationLabel(info).toString() }.getOrDefault(pkg),
            "enabled" to if (action == DeviceSurface.APP_DISABLE) false else if (action == DeviceSurface.APP_ENABLE) true else enabled,
            "uid" to info.uid,
            "versionName" to runCatching { packages.getPackageInfo(pkg, 0).versionName }.getOrNull(),
            "system" to ((info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0),
        )
    }

    private fun setEnabled(packageName: String, enabled: Boolean) {
        // The core runs as root/shell, so the framework call is the one the platform uses; no
        // `pm` shell command is spawned (the engine never builds an argv from caller input).
        val mode = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
        val done = try {
            packages.setApplicationEnabledSetting(packageName, mode, 0)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            fault("devicePackageUnknown")
        }
        if (!done) fault("systemOperationDenied")
    }

    private fun forceStop(packageName: String) {
        val am = context.getSystemService(android.app.ActivityManager::class.java)
            ?: fault("deviceUnavailable")
        // ActivityManager.forceStopPackage is @SystemApi (hidden from the public SDK), and this
        // class only ever compiles into the privileged app_process entry point, which runs without
        // a target SDK: the hidden-API blocklist does not apply there. Reflect, then fall back to
        // the public call so a hardened ROM still does something instead of lying.
        val reflected = runCatching {
            am.javaClass.getMethod("forceStopPackage", String::class.java).invoke(am, packageName)
            true
        }
        if (reflected.getOrDefault(false)) return
        val cause = reflected.exceptionOrNull()
        val security = cause is java.lang.reflect.InvocationTargetException && cause.targetException is SecurityException
        if (!security) {
            try {
                am.killBackgroundProcesses(packageName)
                return
            } catch (_: Throwable) {
                // fall through to the refusal below
            }
        }
        fault("systemOperationDenied")
    }

    // ---- files ------------------------------------------------------------------------

    /**
     * One directory level below [path]. Each entry is `name/` for a directory, `name` otherwise.
     * Symlinks are resolved before the boundary check, so a link inside shared storage that points
     * at `/data/data/...` is refused rather than followed.
     */
    fun list(path: String?, limit: Int?): Map<String, Any?> {
        DeviceSurface.pathProblem(path)?.let { fault(it) }
        val dir = canonical(path!!) ?: fault("pathNotFound")
        if (!dir.isDirectory) fault("notADirectory")
        val children = dir.listFiles() ?: fault("systemOperationDenied")
        val cap = (limit ?: DeviceSurface.MAX_ENTRIES).coerceIn(1, DeviceSurface.MAX_ENTRIES)
        val sorted = children.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        val shown = sorted.take(cap)
        return linkedMapOf(
            "path" to dir.absolutePath,
            "entries" to shown.map { if (it.isDirectory) it.name + "/" else it.name },
            "truncated" to (sorted.size > shown.size),
            "total" to sorted.size,
        )
    }

    /**
     * A byte range of a file, base64 in the response. [maxBytes] is capped by
     * [DeviceSurface.MAX_BYTES] because the frame ceiling is 8 MiB and the bundle carries more
     * than the payload.
     */
    fun read(path: String?, offset: Int?, limit: Int?): Map<String, Any?> {
        DeviceSurface.pathProblem(path)?.let { fault(it) }
        val file = canonical(path!!) ?: fault("pathNotFound")
        if (!file.isFile) fault("notAFile")
        val size = file.length()
        val range = DeviceSurface.byteRange(offset ?: 0, limit ?: DeviceSurface.MAX_BYTES, size) ?: fault("invalidArguments")
        val length = range.second - range.first
        if (length <= 0) return linkedMapOf("path" to file.absolutePath, "total" to size, "offset" to range.first, "data" to ByteArray(0))
        val bytes = ByteArray(length)
        file.inputStream().use { stream ->
            stream.skipFully(range.first.toLong())
            var read = 0
            while (read < length) {
                val n = stream.read(bytes, read, length - read)
                if (n < 0) break
                read += n
            }
            if (read < length) return linkedMapOf("path" to file.absolutePath, "total" to size, "offset" to range.first, "data" to bytes.copyOf(read))
        }
        return linkedMapOf("path" to file.absolutePath, "total" to size, "offset" to range.first, "data" to bytes)
    }

    /**
     * Writes [data] at [offset]; 0 creates or truncates. The bytes go to a sibling `.part-<rand>`
     * first and are renamed into place on success, so an interrupted write never leaves a
     * half-written file where the person expects the original.
     */
    fun write(path: String?, data: ByteArray?, offset: Int?): Map<String, Any?> {
        DeviceSurface.pathProblem(path)?.let { fault(it) }
        val bytes = data ?: fault("invalidArguments")
        if (bytes.size > DeviceSurface.MAX_BYTES) fault("fileTooLarge")
        val target = canonical(path!!)
        if (target == null) {
            // A new file: the parent must already exist and itself be inside the allowed root.
            val parent = File(path).parentFile ?: fault("invalidArguments")
            val realParent = canonical(parent.absolutePath) ?: fault("pathNotFound")
            if (!realParent.isDirectory) fault("notADirectory")
            if (!DeviceSurface.insideAllowedRoot(realParent.absolutePath)) fault("pathOutsideRoot")
            return commit(File(realParent, File(path).name), bytes, offset ?: 0)
        }
        if (!DeviceSurface.insideAllowedRoot(target.absolutePath)) fault("pathOutsideRoot")
        if (target.isDirectory) fault("notAFile")
        return commit(target, bytes, offset ?: 0)
    }

    private fun commit(target: File, bytes: ByteArray, offset: Int): Map<String, Any?> {
        val existing = if (target.exists()) target.length() else 0
        if (offset < 0 || offset.toLong() > existing) fault("invalidArguments")
        val temp = File(target.parentFile, "${target.name}.part-${UUID.randomUUID().toString().take(8)}")
        try {
            if (offset == 0) {
                FileOutputStream(temp).use { it.write(bytes) }
            } else {
                // Appending mid-file: copy the prefix, splice, then rename.
                FileOutputStream(temp).use { out ->
                    target.inputStream().use { input ->
                        var left = offset.toLong()
                        val buffer = ByteArray(64 * 1024)
                        while (left > 0) {
                            val n = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            left -= n
                        }
                    }
                    out.write(bytes)
                }
            }
            if (!temp.renameTo(target)) fault("systemOperationDenied")
            return linkedMapOf("path" to target.absolutePath, "total" to bytes.size.toLong() + offset)
        } finally {
            temp.delete()
        }
    }

    /** The real file, or null when it does not exist; throws when it escapes shared storage. */
    private fun canonical(path: String): File? {
        val file = File(path)
        if (!file.exists()) return null
        val real = try {
            file.canonicalFile
        } catch (_: java.io.IOException) {
            fault("invalidArguments")
        }
        if (!DeviceSurface.insideAllowedRoot(real.absolutePath)) fault("pathOutsideRoot")
        return real
    }

    private fun java.io.InputStream.skipFully(n: Long) {
        var left = n
        val buffer = ByteArray(64 * 1024)
        while (left > 0) {
            val sk = skip(left)
            if (sk > 0) { left -= sk; continue }
            val r = read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            if (r < 0) return
            left -= r
        }
    }

    /** Which uid the engine is running as, for the status line. */
    fun uid(): Int = Process.myUid()
}
