package io.github.ccch1mneyyy.dsh_app.phone.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The device verbs' whole policy surface, without Android.
 *
 * These are the gates that keep a leaked device cookie from becoming an arbitrary
 * command: the allow-listed setting keys, the shared-storage boundary and the app deny
 * list all live here, so they are tested here rather than through the engine.
 */
class DeviceSurfaceTest {

    @Test fun theEngineAcceptsOnlyTheVerbsThisFileDescribes() {
        assertEquals("deviceSetting", Proto.OP_DEVICE_SETTING)
        assertEquals("deviceApp", Proto.OP_DEVICE_APP)
        assertEquals("deviceFiles", Proto.OP_DEVICE_FILES)
        // There is no shell verb on purpose: the engine answers a relay connection, so a
        // command verb would turn one stolen cookie into uid 0 code execution.
        val verbs = Proto::class.java.declaredFields.map { it.name }
        assertFalse(verbs.any { it.contains("SHELL") })
    }

    @Test fun everyReasonCodeTheEngineCanReturnHasASentence() {
        val codes = listOf(
            "deviceKeyNotAllowed", "readbackMismatch", "devicePackageNotAllowed", "devicePackageUnknown",
            "pathOutsideRoot", "pathNotFound", "fileTooLarge", "notADirectory", "notAFile", "deviceRootRequired",
        )
        for (code in codes) {
            val sentence = Reasons.of(code)
            assertNotNull(code, sentence)
            // A missing branch falls back to the generic background-display sentence; that
            // placeholder would tell the model nothing about what to do next.
            assertFalse(code, sentence.contains("不打扰模式暂不可用"))
        }
    }

    @Test fun hiddenSettingsThatWouldGrantTheAgentMorePowerAreNotWritable() {
        for (key in listOf(
            "adb_enabled", "development_settings_enabled", "install_non_market_apps", "mock_location",
            "enabled_accessibility_services", "enabled_notification_listeners", "accessibility_enabled",
        )) {
            for (namespace in DeviceSurface.namespaces()) {
                assertEquals("$namespace/$key must stay out of the allow list", "deviceKeyNotAllowed",
                    DeviceSurface.settingProblem(namespace, key, 0))
            }
        }
    }

    @Test fun allowListedSettingsKeepTheirRange() {
        assertEquals(listOf("system", "secure", "global"), DeviceSurface.namespaces())
        assertNull(DeviceSurface.settingProblem("system", "screen_brightness", 128))
        assertEquals("invalidArguments", DeviceSurface.settingProblem("system", "screen_brightness", 256))
        assertEquals("invalidArguments", DeviceSurface.settingProblem("system", "screen_brightness", -1))
        assertNull(DeviceSurface.settingProblem("global", "zen_mode", 3))
        assertEquals("invalidArguments", DeviceSurface.settingProblem("global", "zen_mode", 4))
        // Reading passes null: the range only applies to writes.
        assertNull(DeviceSurface.settingProblem("system", "screen_off_timeout", null))
        assertEquals("deviceKeyNotAllowed", DeviceSurface.settingProblem("nope", "screen_brightness", null))
        assertNotNull(DeviceSurface.settingKeys("secure"))
    }

    @Test fun filesStayInsideSharedStorageAndOutOfOtherAppsPrivateData() {
        assertNull(DeviceSurface.pathProblem("/sdcard/Download/report.txt"))
        assertNull(DeviceSurface.pathProblem("/storage/emulated/0/DCIM/a.png"))
        assertEquals("invalidArguments", DeviceSurface.pathProblem(null))
        assertEquals("invalidArguments", DeviceSurface.pathProblem(""))
        assertEquals("invalidArguments", DeviceSurface.pathProblem("/sdcard/a\u0000b"))
        assertEquals("pathOutsideRoot", DeviceSurface.pathProblem("sdcard/Download"))
        assertEquals("pathOutsideRoot", DeviceSurface.pathProblem("/sdcard/../data/data/com.example"))
        assertEquals("pathOutsideRoot", DeviceSurface.pathProblem("/data/data/com.example/db"))
        assertFalse(DeviceSurface.insideAllowedRoot("/sdcard/Android/data/com.example/files"))
        assertFalse(DeviceSurface.insideAllowedRoot("/sdcard/Android/obb/com.example"))
        assertFalse(DeviceSurface.insideAllowedRoot("/storage/emulated/0/Android/media/com.example"))
        // [insideAllowedRoot] compares prefixes only; traversal is refused by pathProblem and
        // the resolved path is checked again after canonicalisation.
        assertEquals("pathOutsideRoot", DeviceSurface.pathProblem("/sdcard/other/../../data/data/x"))
        assertFalse(DeviceSurface.insideAllowedRoot("/data/local/tmp"))
        assertFalse(DeviceSurface.insideAllowedRoot("/sdcardfoo"))
        assertEquals("/sdcard", DeviceSurface.FILE_ROOTS.first().trimEnd('/'))
    }

    @Test fun theDenyListAndThisAppAreRefusedBeforeTheEngineSeesThem() {
        val deny = listOf("com.eg.android.AlipayGphone", "com.icbc")
        assertEquals("devicePackageNotAllowed", DeviceSurface.appProblem("com.icbc", "disable", deny))
        assertEquals("devicePackageNotAllowed", DeviceSurface.appProblem(Proto.APP_PACKAGE, "forceStop", deny))
        assertEquals("devicePackageUnknown", DeviceSurface.appProblem("com..broken", "info", deny))
        assertEquals("invalidArguments", DeviceSurface.appProblem("", "info", deny))
        assertEquals("invalidArguments", DeviceSurface.appProblem("a".repeat(256), "info", deny))
        assertEquals("invalidArguments", DeviceSurface.appProblem("com.example", "uninstall", deny))
        assertNull(DeviceSurface.appProblem("com.example", "forceStop", deny))
        assertNull(DeviceSurface.appProblem("com.example", "info", emptyList()))
    }

    @Test fun readsArePagedSoOneFrameCanCarryThem() {
        assertEquals(500, DeviceSurface.MAX_ENTRIES)
        assertEquals(2 * 1024 * 1024, DeviceSurface.MAX_BYTES)
        assertEquals(0 to DeviceSurface.MAX_BYTES, DeviceSurface.byteRange(0, Int.MAX_VALUE, 10L * 1024 * 1024))
        assertEquals(10 to 20, DeviceSurface.byteRange(10, 10, 100))
        assertEquals(90 to 100, DeviceSurface.byteRange(90, 100, 100))
        assertEquals(100 to 100, DeviceSurface.byteRange(100, 10, 100))
        assertNull(DeviceSurface.byteRange(-1, 10, 100))
        assertNull(DeviceSurface.byteRange(0, 0, 100))
        assertNull(DeviceSurface.byteRange(101, 10, 100))
    }
}
