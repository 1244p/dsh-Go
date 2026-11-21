package io.github.ccch1mneyyy.dsh_app.phone

import android.os.Bundle
import io.github.ccch1mneyyy.dsh_app.phone.background.BackgroundControl
import io.github.ccch1mneyyy.dsh_app.phone.background.DeviceSurface
import io.github.ccch1mneyyy.dsh_app.phone.background.Proto

/**
 * [DeviceBridge] over the already published privileged engine.
 *
 * Every call sends one fixed verb and turns `OK=false` into a [DeviceFailure] carrying the engine's
 * reason code, so the core answers with the same sentence the 不打扰模式 surface uses. No verb here
 * starts a channel: [BackgroundControl.device] only uses an engine a user action already started.
 */
internal class PhoneDeviceBridge(private val background: BackgroundControl) : DeviceBridge {

    override fun mode(): String = background.currentMode

    override fun setting(namespace: String?, key: String?): String? {
        val response = send(Proto.OP_DEVICE_SETTING, DeviceSurface.OP_READ) {
            putString(Proto.DEVICE_NAMESPACE, namespace)
            putString(Proto.DEVICE_KEY, key)
        }
        return response.getString(Proto.DEVICE_VALUE)
    }

    override fun writeSetting(namespace: String?, key: String?, value: Int?) {
        send(Proto.OP_DEVICE_SETTING, DeviceSurface.OP_WRITE) {
            putString(Proto.DEVICE_NAMESPACE, namespace)
            putString(Proto.DEVICE_KEY, key)
            putInt(Proto.DEVICE_VALUE, value ?: Int.MIN_VALUE)
        }
    }

    override fun app(packageName: String?, action: String?, denyPackages: List<String>): Map<String, Any?> {
        val response = send(Proto.OP_DEVICE_APP, action) {
            putString(Proto.DEVICE_PACKAGE, packageName)
            putStringArray(Proto.DEVICE_ENTRIES, denyPackages.toTypedArray())
        }
        return linkedMapOf(
            "package" to response.getString(Proto.DEVICE_PACKAGE),
            "label" to response.getString(Proto.APP_LABEL),
            "enabled" to response.getBoolean("enabled"),
            "uid" to response.getInt(Proto.UID),
            "versionName" to response.getString("versionName"),
            "system" to response.getBoolean("system"),
        )
    }

    override fun list(path: String?, limit: Int?): Map<String, Any?> {
        val response = send(Proto.OP_DEVICE_FILES, DeviceSurface.OP_LIST) {
            putString(Proto.DEVICE_PATH, path)
            if (limit != null) putInt(Proto.DEVICE_LIMIT, limit)
        }
        return linkedMapOf(
            "path" to response.getString(Proto.DEVICE_PATH),
            "entries" to (response.getStringArray(Proto.DEVICE_ENTRIES)?.toList() ?: emptyList<String>()),
            "truncated" to response.getBoolean(Proto.DEVICE_TRUNCATED),
            "total" to response.getInt(Proto.DEVICE_TOTAL),
        )
    }

    override fun read(path: String?, offset: Int?, limit: Int?): Map<String, Any?> {
        val response = send(Proto.OP_DEVICE_FILES, DeviceSurface.OP_READ) {
            putString(Proto.DEVICE_PATH, path)
            if (offset != null) putInt(Proto.DEVICE_OFFSET, offset)
            if (limit != null) putInt(Proto.DEVICE_LIMIT, limit)
        }
        return linkedMapOf(
            "path" to response.getString(Proto.DEVICE_PATH),
            "data" to (response.getByteArray(Proto.DEVICE_DATA) ?: ByteArray(0)),
            "offset" to response.getInt(Proto.DEVICE_OFFSET),
            "total" to response.getInt(Proto.DEVICE_TOTAL),
        )
    }

    override fun write(path: String?, data: ByteArray?, offset: Int?): Map<String, Any?> {
        val response = send(Proto.OP_DEVICE_FILES, DeviceSurface.OP_WRITE) {
            putString(Proto.DEVICE_PATH, path)
            putByteArray(Proto.DEVICE_DATA, data)
            if (offset != null) putInt(Proto.DEVICE_OFFSET, offset)
        }
        return linkedMapOf(
            "path" to response.getString(Proto.DEVICE_PATH),
            "total" to response.getInt(Proto.DEVICE_TOTAL),
        )
    }

    override fun status(): Map<String, Any?> = background.status()

    private fun send(op: String, action: String?, fill: Bundle.() -> Unit): Bundle {
        val request = Bundle().apply {
            putString(Proto.OP, op)
            putString(Proto.DEVICE_ACTION, action)
            fill()
        }
        val response = background.device(request) { true }
        if (!response.getBoolean(Proto.OK)) {
            throw DeviceFailure(response.getString(Proto.ERROR) ?: "deviceUnavailable")
        }
        return response
    }
}
