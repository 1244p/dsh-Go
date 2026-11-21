package io.github.ccch1mneyyy.dsh_app.phone

import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four privileged device tools, driven through the real core against a fake bridge.
 *
 * What is asserted here is the part the JVM can check: which confirmations are asked, which
 * refusals happen before the engine is even reached, and that a missing Root channel still
 * answers with something the model can act on instead of dropping the tools.
 */
class PhoneDeviceCoreTest {

    private class FakeBridge : DeviceBridge {
        val writes = mutableListOf<Triple<String, String, Int>>()
        val appCalls = mutableListOf<Triple<String, String, List<String>>>()
        val fileWrites = mutableListOf<Triple<String, ByteArray, Int?>>()
        var mode = "root"
        var failure: String? = null

        override fun mode() = mode

        override fun setting(namespace: String?, key: String?): String? {
            failure?.let { throw DeviceFailure(it) }
            return if (key == "screen_brightness") "120" else null
        }

        override fun writeSetting(namespace: String?, key: String?, value: Int?) {
            failure?.let { throw DeviceFailure(it) }
            writes += Triple(namespace.orEmpty(), key.orEmpty(), value ?: -1)
        }

        override fun app(packageName: String?, action: String?, denyPackages: List<String>): Map<String, Any?> {
            failure?.let { throw DeviceFailure(it) }
            appCalls += Triple(packageName.orEmpty(), action.orEmpty(), denyPackages)
            return linkedMapOf("package" to packageName, "label" to "示例", "enabled" to false, "uid" to 10_000, "system" to false)
        }

        override fun list(path: String?, limit: Int?): Map<String, Any?> {
            failure?.let { throw DeviceFailure(it) }
            return linkedMapOf("path" to path, "entries" to listOf("Download/", "a.txt"), "truncated" to false, "total" to 2)
        }

        override fun read(path: String?, offset: Int?, limit: Int?): Map<String, Any?> {
            failure?.let { throw DeviceFailure(it) }
            return linkedMapOf("path" to path, "data" to "你好".toByteArray(Charsets.UTF_8), "offset" to (offset ?: 0), "total" to 6)
        }

        override fun write(path: String?, data: ByteArray?, offset: Int?): Map<String, Any?> {
            failure?.let { throw DeviceFailure(it) }
            fileWrites += Triple(path.orEmpty(), data ?: ByteArray(0), offset)
            return linkedMapOf("path" to path, "total" to (data?.size ?: 0))
        }

        override fun status(): Map<String, Any?> = linkedMapOf("mode" to mode, "reason" to "已连接")
    }

    private var confirmations = 0
    private val sentences = mutableListOf<String>()
    private var bridge: DeviceBridge? = null
    private val fake = FakeBridge()

    private fun core(policies: PhonePolicies = PhonePolicies()): PhoneCore = PhoneCore(object : PhoneEnv {
        override fun enabled() = true
        override fun policies() = policies
        override fun isLocked() = false
        override fun a11yState() = A11yState.CONNECTED
        override fun backgroundUnavailable() = "unavailable"
        override fun zone() = ZoneId.of("Asia/Shanghai")
        override fun notifyFix(kind: String) {}
        override fun sleep(ms: Long) {}
        override fun driver(screen: PhoneScreen): PhoneDriver? = null
        override fun device(): DeviceBridge? = bridge
        override fun gate() = object : UserGate {
            override fun confirm(request: ConfirmRequest): Decision {
                confirmations++
                sentences += request.sentence
                return Decision.ALLOW_ONCE
            }

            override fun handoff(reason: String, timeoutMs: Long): HandoffResult = error("unexpected handoff")
            override fun status(text: String?) {}
        }
        override fun system() = object : SystemApi {
            override fun apps() = emptyList<AppEntry>()
            override fun isInstalled(pkg: String) = false
            override fun prepareLaunch(app: AppEntry): Either<String, SystemLaunch> = error("unexpected launch")
            override fun prepareUrl(url: String): Either<String, SystemLaunch> = error("unexpected URL")
            override fun prepareIntent(spec: IntentSpec, selectedApp: AppEntry?): Either<String, SystemLaunch> =
                error("unexpected intent")
            override fun launch(plan: SystemLaunch, dispatch: PhoneTasks.SystemDispatch): SysResult =
                error("unexpected launch")
            override fun notify(title: String, text: String): SysResult = error("unexpected notification")
            override fun clipboardSet(text: String): SysResult = error("unexpected clipboard")
            override fun clipboardGet(): SysResult = error("unexpected clipboard")
        }
    }, PhoneTasks(), ActionLog())

    private val opened = mutableListOf<PhoneCore>()

    private fun start(): PhoneCore = core().also { opened += it }

    @After fun close() = opened.forEach { it.worker.shutdown() }

    @Test fun withoutARootChannelTheToolsExplainWhatToDoInsteadOfDisappearing() {
        bridge = null
        val core = start()
        for (name in listOf(PhoneToolCatalog.DEVICE_SETTING, PhoneToolCatalog.DEVICE_APP, PhoneToolCatalog.DEVICE_FILES)) {
            val out = core.call(name, mapOf("namespace" to "system", "key" to "screen_brightness", "package" to "com.example", "path" to "/sdcard/a.txt"))
            assertTrue(name, out.isError)
            assertTrue(name, out.text.contains("Root"))
        }
        assertTrue(PhoneToolCatalog.names.contains(PhoneToolCatalog.DEVICE_STATUS))
        assertEquals(0, confirmations)
    }

    @Test fun deviceStatusReportsTheModeAndKeepsWorkingWithoutABridge() {
        bridge = null
        assertTrue(start().call(PhoneToolCatalog.DEVICE_STATUS, emptyMap()).isError)
        bridge = fake
        val out = start().call(PhoneToolCatalog.DEVICE_STATUS, emptyMap())
        assertFalse(out.text, out.isError)
        assertTrue(out.text, out.text.contains("root"))
    }

    @Test fun readingASettingNeedsNoConfirmationButWritingDoes() {
        bridge = fake
        val core = start()
        val read = core.call(PhoneToolCatalog.DEVICE_SETTING, mapOf("namespace" to "system", "key" to "screen_brightness"))
        assertFalse(read.text, read.isError)
        assertTrue(read.text, read.text.contains("120"))
        assertEquals(0, confirmations)
        assertEquals(0, fake.writes.size)

        val write = core.call(PhoneToolCatalog.DEVICE_SETTING, mapOf("namespace" to "system", "key" to "screen_brightness", "action" to "write", "value" to 40))
        assertFalse(write.text, write.isError)
        assertEquals(1, confirmations)
        assertEquals(listOf(Triple("system", "screen_brightness", 40)), fake.writes)
    }

    @Test fun aDeniedPackageIsRefusedBeforeTheEngineSeesIt() {
        bridge = fake
        val core = start()
        for (action in listOf("disable", "forceStop", "enable")) {
            val out = core.call(PhoneToolCatalog.DEVICE_APP, mapOf("package" to "com.eg.android.AlipayGphone", "action" to action))
            assertTrue(action, out.isError)
        }
        assertTrue(fake.appCalls.isEmpty())
        assertEquals(0, confirmations)
    }

    @Test fun appChangesAskOnceAndCarryTheDenyListToTheEngine() {
        bridge = fake
        val core = start()
        val info = core.call(PhoneToolCatalog.DEVICE_APP, mapOf("package" to "com.example.app", "action" to "info"))
        assertFalse(info.text, info.isError)
        assertEquals(0, confirmations)
        assertEquals("info", fake.appCalls.single().second)

        val disable = core.call(PhoneToolCatalog.DEVICE_APP, mapOf("package" to "com.example.app", "action" to "disable"))
        assertFalse(disable.text, disable.isError)
        assertEquals(1, confirmations)
        assertEquals("disable", fake.appCalls.last().second)
        // The engine has no policy of its own: the person's deny list rides with every call.
        assertTrue(fake.appCalls.last().third.contains("com.eg.android.AlipayGphone"))
    }

    @Test fun writingAFileKeepsUtf8BytesAndAsksFirst() {
        bridge = fake
        val out = start().call(
            PhoneToolCatalog.DEVICE_FILES,
            mapOf("action" to "write", "path" to "/sdcard/Download/a.txt", "content" to "你好"),
        )
        assertFalse(out.text, out.isError)
        assertEquals(1, confirmations)
        assertEquals("你好", String(fake.fileWrites.single().second, Charsets.UTF_8))
    }

    @Test fun readingAFileDecodesUtf8() {
        bridge = fake
        val out = start().call(PhoneToolCatalog.DEVICE_FILES, mapOf("action" to "read", "path" to "/sdcard/Download/a.txt"))
        assertFalse(out.text, out.isError)
        assertTrue(out.text, out.text.contains("你好"))
    }

    @Test fun listReportsWhatIsThere() {
        bridge = fake
        val out = start().call(PhoneToolCatalog.DEVICE_FILES, mapOf("action" to "list", "path" to "/sdcard/Download"))
        assertFalse(out.text, out.isError)
        assertTrue(out.text, out.text.contains("a.txt"))
    }

    @Test fun anEngineRefusalBecomesASentenceAndNeverAnException() {
        bridge = fake
        fake.failure = "deviceKeyNotAllowed"
        val out = start().call(PhoneToolCatalog.DEVICE_SETTING, mapOf("namespace" to "secure", "key" to "adb_enabled"))
        assertTrue(out.isError)
        assertFalse(out.text, out.text.contains("不打扰模式暂不可用"))
        assertTrue(out.text, out.text.contains("白名单") || out.text.contains("名单"))
    }
}
