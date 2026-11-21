package io.github.ccch1mneyyy.dsh_app.xposed

import io.github.ccch1mneyyy.dsh_app.phone.a11y.A11yKeeper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipFile

/**
 * What the framework reads out of the APK, and the two names that have to survive a process
 * boundary. A module whose metadata is wrong simply never appears in LSPosed's list, with no error
 * anywhere — so the file contents are pinned here instead of being discovered on a phone.
 */
class ModuleArtifactTest {
    @Test fun theArmSwitchKeyIsTheSameStringOnBothSidesOfTheBoundary() {
        // A11yKeeper writes it from the app's process; the module reads it inside system_server,
        // where it cannot import that class. A typo on either side disarms the guard silently.
        assertEquals(A11yKeeper.GUARD_KEY, AccessibilityProtection.GUARD_KEY)
    }

    @Test fun theMetadataNamesTheEntryClassThatActuallyExists() {
        val dir = "src/arm64/resources/META-INF/xposed"
        val init = java.io.File(dir, "java_init.list").readLines().filter { it.isNotBlank() }
        assertEquals(listOf("io.github.ccch1mneyyy.dsh_app.xposed.DshModule"), init)

        // Read the declaration out of the source rather than DshModule::class: the libxposed API is
        // compileOnly (the framework supplies it at runtime), so the class cannot be loaded here.
        val source = java.io.File("src/main/kotlin/io/github/ccch1mneyyy/dsh_app/xposed/DshModule.kt")
            .readLines()
        val pkg = source.first { it.startsWith("package ") }.removePrefix("package ").trim()
        val cls = source.first { Regex("""^class \w+ : XposedModule\(\)""").containsMatchIn(it) }
            .substringAfter("class ").substringBefore(' ').trim()
        assertEquals("$pkg.$cls", init.single())
    }

    @Test fun theMetadataAsksForTheApiTheModuleIsWrittenAgainst() {
        val props = java.io.File("src/arm64/resources/META-INF/xposed/module.prop")
            .readLines()
            .filter { "=" in it }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        // 102 is what DshModule overrides (onSystemServerStarting, HookBuilder.setId). Older
        // frameworks would load it and never call that method.
        assertEquals("102", props["minApiVersion"])
        assertEquals("102", props["targetApiVersion"])
        // staticScope: no code selector in the manager, so the list must be complete up front.
        assertEquals("true", props["staticScope"])
        assertEquals("protective", props["exceptionMode"])
    }

    @Test fun theScopeIsOnlyTheProcessThatCanKeepTheList() {
        val scope = java.io.File("src/arm64/resources/META-INF/xposed/scope.list")
            .readLines()
            .filter { it.isNotBlank() }
        assertEquals(listOf("system"), scope)
    }

    @Test fun theMetadataLivesOnlyInTheArm64Flavor() {
        // Both flavors come from the same source set; if this folder ever moves to src/main, the
        // x86 build would claim to be an LSPosed module that its own framework cannot load.
        assertTrue(java.io.File("src/arm64/resources/META-INF/xposed").isDirectory)
        assertFalse(java.io.File("src/main/resources/META-INF/xposed").exists())
    }
}
