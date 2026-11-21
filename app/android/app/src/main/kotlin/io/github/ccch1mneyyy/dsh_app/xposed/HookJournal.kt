package io.github.ccch1mneyyy.dsh_app.xposed

import io.github.libxposed.api.XposedInterface

/**
 * What one group of hooks did, kept as data so the framework can log one line per group and the
 * person can read it in LSPosed's log instead of watching for a crash.
 *
 * A missing hook is not an error: ColorOS 16 and 17 move these methods between releases, and the
 * module must stay quiet on a ROM it does not know rather than refuse to load.
 */
internal enum class HookStatus { INSTALLED, MISSING, FAILED }

internal data class HookOutcome(val id: String, val description: String, val status: HookStatus, val detail: String? = null)

/** Collects outcomes for one group; [summary] is what gets logged. */
internal class HookJournal(private val group: String) {
    private val outcomes = mutableListOf<HookOutcome>()

    fun installed(id: String, description: String) {
        outcomes += HookOutcome(id, description, HookStatus.INSTALLED)
    }

    fun missing(id: String, description: String, detail: String? = null) {
        outcomes += HookOutcome(id, description, HookStatus.MISSING, detail)
    }

    fun failed(id: String, description: String, error: Throwable) {
        outcomes += HookOutcome(id, description, HookStatus.FAILED, error.javaClass.simpleName + ": " + error.message)
    }

    fun summary(): String {
        val installed = outcomes.count { it.status == HookStatus.INSTALLED }
        val missing = outcomes.count { it.status == HookStatus.MISSING }
        val failed = outcomes.count { it.status == HookStatus.FAILED }
        val detail = outcomes.filter { it.status != HookStatus.INSTALLED }
            .joinToString("; ") { "${it.id}=${it.status.name.lowercase()}${it.detail?.let { d -> " ($d)" } ?: ""}" }
        return "$group: $installed installed, $missing missing, $failed failed" +
            if (detail.isEmpty()) "" else " — $detail"
    }

    /** Installs one hook, turning every failure mode into a journal entry instead of an exception. */
    fun hook(
        module: XposedInterface,
        id: String,
        description: String,
        target: java.lang.reflect.Executable?,
        body: (XposedInterface.Chain) -> Any?,
    ): Boolean {
        if (target == null) {
            missing(id, description, "method not found")
            return false
        }
        return try {
            module.hook(target)
                .setId(id)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain -> body(chain) }
            installed(id, description)
            true
        } catch (error: Throwable) {
            failed(id, description, error)
            false
        }
    }
}
