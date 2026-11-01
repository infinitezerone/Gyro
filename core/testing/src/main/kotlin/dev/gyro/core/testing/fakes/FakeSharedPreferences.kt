package dev.gyro.core.testing.fakes

import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe, in-memory implementation of [SharedPreferences] for local JVM unit testing.
 * Zero Android framework or Robolectric dependencies.
 */
class FakeSharedPreferences(
    initialValues: Map<String, Any?> = emptyMap()
) : SharedPreferences {

    private val storage = ConcurrentHashMap<String, Any?>(initialValues)
    private val listeners = CopyOnWriteArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): MutableMap<String, *> = HashMap(storage)

    override fun getString(key: String?, defValue: String?): String? {
        val value = storage[key]
        return if (value is String) value else defValue
    }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        val value = storage[key]
        return if (value is Set<*>) (value as Set<String>).toMutableSet() else defValues
    }

    override fun getInt(key: String?, defValue: Int): Int {
        val value = storage[key]
        return when (value) {
            is Int -> value
            is Number -> value.toInt()
            else -> defValue
        }
    }

    override fun getLong(key: String?, defValue: Long): Long {
        val value = storage[key]
        return when (value) {
            is Long -> value
            is Number -> value.toLong()
            else -> defValue
        }
    }

    override fun getFloat(key: String?, defValue: Float): Float {
        val value = storage[key]
        return when (value) {
            is Float -> value
            is Number -> value.toFloat()
            else -> defValue
        }
    }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
        val value = storage[key]
        return if (value is Boolean) value else defValue
    }

    override fun contains(key: String?): Boolean = storage.containsKey(key)

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        listeners.remove(listener)
    }

    private fun notifyListeners(keys: Set<String>) {
        for (listener in listeners) {
            for (key in keys) {
                listener.onSharedPreferenceChanged(this, key)
            }
        }
    }

    private inner class EditorImpl : SharedPreferences.Editor {
        private val modifications = mutableMapOf<String, Any?>()
        private var clearRequested = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) modifications[key] = value
            return this
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            if (key != null) modifications[key] = values?.toSet()
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) modifications[key] = value
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) modifications[key] = value
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) modifications[key] = value
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) modifications[key] = value
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) modifications[key] = REMOVED_MARKER
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearRequested = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            val changedKeys = mutableSetOf<String>()
            synchronized(storage) {
                if (clearRequested) {
                    changedKeys.addAll(storage.keys)
                    storage.clear()
                }
                for ((k, v) in modifications) {
                    if (v === REMOVED_MARKER) {
                        if (storage.remove(k) != null) changedKeys.add(k)
                    } else if (v != null) {
                        storage[k] = v
                        changedKeys.add(k)
                    } else {
                        if (storage.remove(k) != null) changedKeys.add(k)
                    }
                }
            }
            notifyListeners(changedKeys)
        }
    }

    companion object {
        private val REMOVED_MARKER = Any()
    }
}
