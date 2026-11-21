package io.github.ccch1mneyyy.dsh_app.runtime

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.json.JSONObject

internal fun checkEngineCompatibility(
    baseDir: File,
    raw: JSONObject,
    version: String,
    abi: String,
    onLog: (String) -> Unit = {},
): EngineUpgradeSpec {
    val rootfs = File(baseDir, "rootfs")
    val staging = File(baseDir, "staging")
    if (Files.isSymbolicLink(rootfs.toPath()) || File(baseDir, "home.carry").exists() ||
        File(baseDir, "rootfs.old").exists()) throw RuntimeUpgradeBlocked("legacy_interrupted")
    val next = parseUpgrade(raw)
    val shipped = readRuntimeInfo(staging)
    if (parseUpgrade(shipped) != next || shipped.optString("runtimeVersion") != version) {
        onLog("[dsh-app] upgrade refused: shipped runtime info disagrees with the manifest")
        throw RuntimeUpgradeBlocked("manifest")
    }
    val previous = readRuntimeInfo(rootfs)
    val previousAbi = previous.optString("abi")
    val shippedAbi = shipped.optString("abi")
    val osNow = osIdentity(rootfs)
    val osNext = osIdentity(staging)
    if (previousAbi != abi || shippedAbi != abi || osNow != osNext) {
        // The wire reason stays the coarse "base" the UI translates; this line is what
        // a bug report needs, because six different inputs all refuse the same way.
        onLog(
            "[dsh-app] upgrade refused: abi installed=" + previousAbi.ifEmpty { "missing" } +
                " shipped=" + shippedAbi.ifEmpty { "missing" } + " expected=" + abi +
                ", os installed=" + osNow.joinToString("/").ifEmpty { "unreadable" } +
                " shipped=" + osNext.joinToString("/").ifEmpty { "unreadable" },
        )
        throw RuntimeUpgradeBlocked("base")
    }
    if (previous.has("upgrade")) {
        val installedBase = parseUpgrade(previous).baseId
        if (installedBase != next.baseId) {
            // The base id hashes every deb in the closure, but an engine upgrade replaces
            // only [ENGINE_PATHS] — /usr, /lib and dpkg are the user's system and stay put.
            // A base that differs only in packages outside those paths is therefore not a
            // reason to refuse: re-resolving a dead deb pin (UPDATE_DEB_LOCK=1) moves
            // ca-certificates or perl and the id changes while nothing the engine loads does.
            // So fall back to what the engine actually depends on: the intermediate and
            // C/C++/TLS shared libraries must be byte-identical in both trees.
            val drift = runtimeLibraryDrift(rootfs, staging)
            if (drift != null) {
                onLog(
                    "[dsh-app] upgrade refused: base id installed=" + installedBase.take(12) +
                        " shipped=" + next.baseId.take(12) + ", runtime libraries " + drift,
                )
                throw RuntimeUpgradeBlocked("base")
            }
            onLog(
                "[dsh-app] upgrade allowed: base id moved " + installedBase.take(12) + " to " +
                    next.baseId.take(12) + " but every runtime library the engine loads is unchanged",
            )
        }
    } else {
        // One-time adoption of old bundles. Do not infer compatibility just
        // from rNN or Node versions. Keep Python and all user pip packages.
        val ubuntuNow = previous.optString("ubuntuVersion")
        val pythonNow = digestOf(File(rootfs, "opt/python/bin/python3"))
        val pythonNext = digestOf(File(staging, "opt/python/bin/python3"))
        if (ubuntuNow != next.ubuntuVersion || pythonNow == null || pythonNow != pythonNext) {
            onLog(
                "[dsh-app] upgrade refused: ubuntu installed=" + ubuntuNow.ifEmpty { "missing" } +
                    " shipped=" + next.ubuntuVersion +
                    ", python installed=" + (pythonNow?.take(12) ?: "missing") +
                    " shipped=" + (pythonNext?.take(12) ?: "missing"),
            )
            throw RuntimeUpgradeBlocked("base")
        }
    }
    return next
}

private fun readRuntimeInfo(tree: File): JSONObject = try {
    JSONObject(File(tree, "etc/dsh-app/runtime.json").readText())
} catch (_: Exception) {
    throw RuntimeUpgradeBlocked("manifest")
}

private fun parseUpgrade(json: JSONObject): EngineUpgradeSpec = try {
    val upgrade = json.getJSONObject("upgrade")
    if (upgrade.getInt("schema") != 1) throw RuntimeUpgradeBlocked("manifest")
    val paths = upgrade.getJSONArray("paths")
    EngineUpgradeSpec(upgrade.getString("baseId"), upgrade.getString("ubuntuVersion"),
        List(paths.length()) { paths.getString(it) }).also { it.validate() }
} catch (_: Exception) {
    throw RuntimeUpgradeBlocked("manifest")
}

private fun osIdentity(tree: File): List<String> = try {
    val rows = File(tree, "etc/os-release").readLines()
    listOf("ID", "VERSION_ID").map { key ->
        rows.single { it.startsWith("$key=") }.substringAfter('=').trim().trim('"', '\'')
    }
} catch (_: Exception) {
    throw RuntimeUpgradeBlocked("base")
}

/**
 * The shared libraries a replaced engine binary loads, in every layout Ubuntu uses for the
 * two ABIs this app ships. Byte identity here is what makes an engine-only swap safe when
 * the deb closure moved: everything else the base provides is left untouched.
 */
private val RUNTIME_LIBRARIES = listOf(
    "libc.so.6", "libm.so.6", "libstdc++.so.6", "libgcc_s.so.1",
    "libz.so.1", "libssl.so.3", "libcrypto.so.3",
)
private val LIBRARY_DIRS = listOf(
    "lib/aarch64-linux-gnu", "usr/lib/aarch64-linux-gnu",
    "lib/x86_64-linux-gnu", "usr/lib/x86_64-linux-gnu",
)

/**
 * Names the first runtime library that is missing or differs, or null when the two trees
 * agree. Both trees must carry every library: an absent file is not "the same".
 */
private fun runtimeLibraryDrift(old: File, next: File): String? {
    for (name in RUNTIME_LIBRARIES) {
        val installed = LIBRARY_DIRS.asSequence().map { File(old, "$it/$name") }.firstOrNull { it.isFile }
        val shipped = LIBRARY_DIRS.asSequence().map { File(next, "$it/$name") }.firstOrNull { it.isFile }
        if (installed == null || shipped == null) {
            return name + (if (installed == null) " missing from the installed system" else " missing from the payload")
        }
        if (digestOf(installed) != digestOf(shipped)) return name + " differs"
    }
    return null
}

/** SHA-256 of one file, or null when it is absent or unreadable. */
private fun digestOf(file: File): String? = try {
    val hash = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(65536)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            hash.update(buffer, 0, count)
        }
    }
    hash.digest().joinToString("") { "%02x".format(it) }
} catch (_: Exception) { null }

