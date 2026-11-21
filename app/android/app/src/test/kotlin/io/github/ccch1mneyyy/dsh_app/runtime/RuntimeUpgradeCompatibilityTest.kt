package io.github.ccch1mneyyy.dsh_app.runtime

import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class RuntimeUpgradeCompatibilityTest {
    private val base = Files.createTempDirectory("upgrade-compatibility").toFile()
    private val abi = "arm64-v8a"
    private fun descriptor(id: String = "a".repeat(64)) = JSONObject()
        .put("schema", 1).put("baseId", id).put("ubuntuVersion", "24.04.5").put("paths", JSONArray(ENGINE_PATHS))
    private fun info(version: String, layered: Boolean = true): JSONObject = JSONObject()
        .put("runtimeVersion", version).put("abi", abi).put("ubuntuVersion", "24.04.5")
        .apply { if (layered) put("upgrade", descriptor()) }
    private val next = info("new")

    @After fun cleanup() { base.deleteRecursively() }

    private fun write(path: String, text: String) = File(base, path).apply { parentFile!!.mkdirs(); writeText(text) }
    private fun installed(layered: Boolean = true) {
        write("rootfs/etc/dsh-app/runtime.json", info("old", layered).toString())
        write("staging/etc/dsh-app/runtime.json", next.toString())
        for (tree in listOf("rootfs", "staging")) {
            write("$tree/etc/os-release", "ID=ubuntu\nVERSION_ID=\"24.04\"\n")
            write("$tree/opt/python/bin/python3", "same python binary")
        }
        write("rootfs/usr/bin/gh", "user tool")
        write("rootfs/var/lib/dpkg/status", "user packages")
        write("rootfs.version", "old")
    }

    /** The libraries both trees must agree on before a moved base id may be accepted. */
    private fun libraries(same: Boolean = true) {
        for (tree in listOf("rootfs", "staging")) {
            for (name in listOf("libc.so.6", "libm.so.6", "libstdc++.so.6", "libgcc_s.so.1",
                                "libz.so.1", "libssl.so.3", "libcrypto.so.3")) {
                write("$tree/lib/aarch64-linux-gnu/$name", "shared $name")
            }
        }
        if (!same) write("rootfs/lib/aarch64-linux-gnu/libssl.so.3", "an older TLS library")
    }
    private fun check() = checkEngineCompatibility(base, next, "new", abi)
    private fun rejected(reason: String) {
        val error = assertThrows(RuntimeUpgradeBlocked::class.java) { check() }
        assertEquals("runtime_upgrade_blocked:$reason", error.message)
        assertEquals("old", File(base, "rootfs.version").readText())
        assertEquals("user tool", File(base, "rootfs/usr/bin/gh").readText())
        assertEquals("user packages", File(base, "rootfs/var/lib/dpkg/status").readText())
    }

    @Test fun sameSystemAllowsIndependentEngineVersions() {
        installed()
        assertEquals("a".repeat(64), check().baseId)
    }

    @Test fun legacyRuntimeIsAdoptedWithoutReplacingItsSystemOrPythonPackages() {
        installed(layered = false)
        write("rootfs/opt/python/lib/site-packages/custom.py", "user pip package")
        assertEquals(ENGINE_PATHS, check().paths)
        assertEquals("user pip package", File(base, "rootfs/opt/python/lib/site-packages/custom.py").readText())
    }

    @Test fun changedFactorySystemStopsWithoutResetting() {
        installed()
        // A different base is only a refusal when the engine's own libraries moved with it:
        // that is the case this test means to pin. The reason is spelled out in the log.
        libraries(same = false)
        write("rootfs/etc/dsh-app/runtime.json", info("old").put("upgrade", descriptor("b".repeat(64))).toString())
        rejected("base")
    }

    @Test fun aBaseThatDiffersOnlyOutsideTheEnginePathsCannotBeVerifiedAndIsStillRefused() {
        installed()
        // Without the libraries in both trees there is nothing to compare, and "cannot tell"
        // must behave like "not compatible" rather than like "probably fine".
        write("rootfs/etc/dsh-app/runtime.json", info("old").put("upgrade", descriptor("b".repeat(64))).toString())
        rejected("base")
    }

    /**
     * A re-resolved deb pin moves ca-certificates or perl, so the base id changes while
     * nothing the engine loads does. Refusing there left a device unable to update at all.
     */
    @Test fun movedBaseIdIsAcceptedWhenEveryRuntimeLibraryIsUnchanged() {
        installed()
        libraries()
        write("rootfs/etc/dsh-app/runtime.json", info("old").put("upgrade", descriptor("b".repeat(64))).toString())
        val log = mutableListOf<String>()
        assertEquals("a".repeat(64), checkEngineCompatibility(base, next, "new", abi) { log += it }.baseId)
        assertTrue(log.single(), log.single().contains("every runtime library the engine loads is unchanged"))
    }

    @Test fun movedBaseIdIsStillRefusedWhenARuntimeLibraryDiffers() {
        installed()
        libraries(same = false)
        write("rootfs/etc/dsh-app/runtime.json", info("old").put("upgrade", descriptor("b".repeat(64))).toString())
        val log = mutableListOf<String>()
        assertThrows(RuntimeUpgradeBlocked::class.java) {
            checkEngineCompatibility(base, next, "new", abi) { log += it }
        }
        assertTrue(log.single(), log.single().contains("libssl.so.3 differs"))
        assertEquals("user tool", File(base, "rootfs/usr/bin/gh").readText())
    }

    @Test fun wrongAbiOrUserDistributionUpgradeIsNotSilentlyAccepted() {
        installed()
        write("rootfs/etc/dsh-app/runtime.json", info("old").put("abi", "x86_64").toString())
        rejected("base")
        installed()
        write("rootfs/etc/os-release", "ID=ubuntu\nVERSION_ID=26.04\n")
        rejected("base")
    }

    @Test fun legacyPythonOrUbuntuMismatchRequiresBackupAndMigration() {
        installed(layered = false)
        write("rootfs/opt/python/bin/python3", "different binary")
        rejected("base")
        installed(layered = false)
        write("rootfs/etc/dsh-app/runtime.json", info("old", false).put("ubuntuVersion", "24.04.4").toString())
        rejected("base")
    }

    @Test fun unknownMissingOrInconsistentManifestsNeverFallBackToFullRootfsReplacement() {
        installed()
        write("rootfs/etc/dsh-app/runtime.json", "not JSON")
        rejected("manifest")
        installed()
        next.getJSONObject("upgrade").put("schema", 99)
        rejected("manifest")
        next.put("upgrade", descriptor())
        write("staging/etc/dsh-app/runtime.json", info("wrong-version").toString())
        rejected("manifest")
        File(base, "rootfs/etc/dsh-app/runtime.json").delete()
        rejected("manifest")
    }

    /**
     * The UI only ever sees the coarse `base`, because a phone user can act on "update the
     * engine" but not on "your python binary differs". These two cases refused with the same
     * sentence before, which made a real report impossible to diagnose from a log.
     */
    @Test fun refusalLogNamesTheInputThatDiffered() {
        installed()
        write("rootfs/etc/dsh-app/runtime.json", info("old").put("abi", "x86_64").toString())
        val abiLog = mutableListOf<String>()
        assertThrows(RuntimeUpgradeBlocked::class.java) {
            checkEngineCompatibility(base, next, "new", abi) { abiLog += it }
        }
        val abiLine = abiLog.single()
        assertTrue(abiLine, abiLine.contains("installed=x86_64"))
        assertTrue(abiLine, abiLine.contains("expected=arm64-v8a"))

        installed(layered = false)
        write("rootfs/opt/python/bin/python3", "different binary")
        val pythonLog = mutableListOf<String>()
        assertThrows(RuntimeUpgradeBlocked::class.java) {
            checkEngineCompatibility(base, next, "new", abi) { pythonLog += it }
        }
        val pythonLine = pythonLog.single()
        assertTrue(pythonLine, pythonLine.contains("python installed="))
        assertFalse(pythonLine, pythonLine.contains("python installed=missing"))
        assertTrue(pythonLine, pythonLine.contains("ubuntu installed=24.04.5"))
    }

    @Test fun interruptedOldRootfsSwapIsPreservedForRecoveryInsteadOfBeingWiped() {
        installed()
        write("home.carry/user", "carried home")
        rejected("legacy_interrupted")
        assertEquals("carried home", File(base, "home.carry/user").readText())
    }
}
