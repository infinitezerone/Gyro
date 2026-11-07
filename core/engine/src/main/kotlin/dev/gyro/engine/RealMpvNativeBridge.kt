package dev.gyro.engine

import android.content.Context
import android.view.Surface
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [MpvNativeBridge] delegating directly to [GyroNative] JNI.
 * Supports both companion singleton access ([RealMpvNativeBridge]) and constructor instantiation ([RealMpvNativeBridge()]).
 */
@Singleton
open class RealMpvNativeBridge @Inject constructor() : MpvNativeBridge {

    companion object : RealMpvNativeBridge()

    override val events
        get() = GyroNative.events

    override fun create(appctx: Context?) {
        requireNotNull(appctx) { "Context is required to initialize native mpv instance" }
        GyroNative.create(appctx)
    }

    override fun init() {
        GyroNative.init()
    }

    override fun destroy() {
        GyroNative.destroy()
    }

    override fun attachSurface(surface: Surface) {
        GyroNative.attachSurface(surface)
    }

    override fun detachSurface() {
        GyroNative.detachSurface()
    }

    override fun redraw() {
        GyroNative.redraw()
    }

    override fun command(cmd: Array<out String>) {
        GyroNative.command(cmd)
    }

    override fun setOptionString(name: String, value: String): Int {
        return GyroNative.setOptionString(name, value)
    }

    override fun getPropertyString(name: String): String? {
        return GyroNative.getPropertyString(name)
    }

    override fun getPropertyLong(name: String): Long? {
        return GyroNative.getLong(name)
    }

    override fun getPropertyDouble(name: String): Double? {
        return GyroNative.getDouble(name)
    }

    override fun getPropertyBoolean(name: String): Boolean? {
        return GyroNative.getBoolean(name)
    }

    override fun setPropertyString(name: String, value: String) {
        GyroNative.setPropertyString(name, value)
    }

    override fun setPropertyLong(name: String, value: Long) {
        GyroNative.setPropertyLong(name, value)
    }

    override fun setPropertyDouble(name: String, value: Double) {
        GyroNative.setPropertyDouble(name, value)
    }

    override fun setPropertyBoolean(name: String, value: Boolean) {
        GyroNative.setPropertyBoolean(name, value)
    }

    override fun observeProperty(name: String, format: Int) {
        GyroNative.observeProperty(name, format)
    }

    override fun isZeroCopySafe(): Boolean {
        return GyroNative.isZeroCopySafe()
    }

    override fun getGpuRenderer(): String {
        return GyroNative.getGpuRenderer()
    }
}
