package dev.gyro.core.testing.fakes

import android.content.Context
import android.view.Surface
import dev.gyro.engine.MpvEvent
import dev.gyro.engine.MpvEventBus
import dev.gyro.engine.MpvNativeBridge
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * High-fidelity, thread-safe test fake implementing [MpvNativeBridge] for pure JVM unit tests.
 *
 * Concurrency Guarantees:
 * - Thread-safe command recording via [CopyOnWriteArrayList]
 * - Thread-safe lifecycle and surface counters via [AtomicInteger]
 * - Thread-safe option and observation stores via [ConcurrentHashMap]
 * - Thread-safe property store via [ConcurrentPropertyMap] supporting nullable values and safe iteration
 * - Synchronized [reset] guaranteeing atomic clearance of all state
 *
 * Records:
 * - All executed commands with exact argument lists
 * - All pre-init options and runtime property mutations
 * - All observed properties and formats
 * - Surface attachments and lifecycle counts
 *
 * Simulates:
 * - Event callbacks ([emitEvent], [emitFileLoaded], [emitTracksChanged], [emitEndFile], [emitShutdown])
 * - Property value emissions ([emitPropertyLong], [emitPropertyDouble], [emitPropertyFlag], [emitPropertyString])
 * - Configurable GPU probe results ([isZeroCopySafe], [gpuRenderer])
 */
class FakeMpvNativeBridge(
    @Volatile var isZeroCopySafeResult: Boolean = true,
    @Volatile var gpuRendererResult: String = "Adreno (TM) 750"
) : MpvNativeBridge {

    private val bus = MpvEventBus()
    override val events = bus.events
    val eventSubscriberCount: Int get() = bus.subscriptionCount.value

    // Call counters backed by AtomicInteger for thread-safety
    private val _createCount = AtomicInteger(0)
    val createCount: Int get() = _createCount.get()

    private val _initCount = AtomicInteger(0)
    val initCount: Int get() = _initCount.get()

    private val _destroyCount = AtomicInteger(0)
    val destroyCount: Int get() = _destroyCount.get()

    private val _attachSurfaceCount = AtomicInteger(0)
    val attachSurfaceCount: Int get() = _attachSurfaceCount.get()

    private val _detachSurfaceCount = AtomicInteger(0)
    val detachSurfaceCount: Int get() = _detachSurfaceCount.get()

    private val _redrawCount = AtomicInteger(0)
    val redrawCount: Int get() = _redrawCount.get()

    // Recorded invocations & state store using concurrent thread-safe collections
    val commands: CopyOnWriteArrayList<List<String>> = CopyOnWriteArrayList()
    val recordedCommands: CopyOnWriteArrayList<List<String>> get() = commands

    val options: ConcurrentHashMap<String, String> = ConcurrentHashMap()
    val recordedOptions: ConcurrentHashMap<String, String> get() = options

    val properties: ConcurrentPropertyMap = ConcurrentPropertyMap()
    val recordedProperties: ConcurrentPropertyMap get() = properties

    val observedProperties: ConcurrentHashMap<String, Int> = ConcurrentHashMap()
    val attachedSurfaces: CopyOnWriteArrayList<Surface> = CopyOnWriteArrayList()

    override fun create(appctx: Context?) {
        _createCount.incrementAndGet()
    }

    override fun init() {
        _initCount.incrementAndGet()
    }

    override fun destroy() {
        _destroyCount.incrementAndGet()
    }

    override fun attachSurface(surface: Surface) {
        _attachSurfaceCount.incrementAndGet()
        attachedSurfaces.add(surface)
    }

    override fun detachSurface() {
        _detachSurfaceCount.incrementAndGet()
    }

    override fun redraw() {
        _redrawCount.incrementAndGet()
    }

    override fun command(cmd: Array<out String>) {
        commands.add(cmd.toList())
    }

    override fun setOptionString(name: String, value: String): Int {
        options[name] = value
        return 0
    }

    override fun getPropertyString(name: String): String? {
        return properties[name]?.toString()
    }

    override fun getPropertyLong(name: String): Long? {
        val v = properties[name] ?: return null
        return when (v) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull()
            else -> null
        }
    }

    override fun getPropertyDouble(name: String): Double? {
        val v = properties[name] ?: return null
        return when (v) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }
    }

    override fun getPropertyBoolean(name: String): Boolean? {
        val v = properties[name] ?: return null
        return when (v) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> when {
                v.equals("yes", ignoreCase = true) || v.equals("true", ignoreCase = true) -> true
                v.equals("no", ignoreCase = true) || v.equals("false", ignoreCase = true) -> false
                else -> null
            }
            else -> null
        }
    }

    override fun setPropertyString(name: String, value: String) {
        properties[name] = value
    }

    override fun setPropertyLong(name: String, value: Long) {
        properties[name] = value
    }

    override fun setPropertyDouble(name: String, value: Double) {
        properties[name] = value
    }

    override fun setPropertyBoolean(name: String, value: Boolean) {
        properties[name] = value
    }

    override fun observeProperty(name: String, format: Int) {
        observedProperties[name] = format
    }

    override fun isZeroCopySafe(): Boolean = isZeroCopySafeResult

    override fun getGpuRenderer(): String = gpuRendererResult

    // ---------- Test Simulation & Emulation Helpers ----------

    /**
     * Pre-seeds a property in the internal store for subsequent getProperty* calls.
     * Passing null removes the property from the store to prevent NullPointerException.
     */
    fun setProperty(name: String, value: Any?) {
        if (value == null) {
            properties.remove(name)
        } else {
            properties[name] = value
        }
    }

    /**
     * Checks if a property was registered for observation.
     */
    fun isPropertyObserved(name: String): Boolean = observedProperties.containsKey(name)

    /**
     * Checks if a property was registered for observation with a specific format.
     */
    fun isPropertyObserved(name: String, format: Int): Boolean = observedProperties[name] == format

    /**
     * Publishes an mpv lifecycle event to active flow subscribers.
     */
    fun emitEvent(eventId: Int) {
        bus.publish(MpvEvent.Lifecycle(eventId))
    }

    /**
     * Simulates FILE_LOADED event with optional title and hwdec strings.
     */
    fun emitFileLoaded(mediaTitle: String = "Test Video", hwdec: String = "mediacodec") {
        properties["media-title"] = mediaTitle
        properties["hwdec-current"] = hwdec
        emitEvent(MpvNativeBridge.Event.FILE_LOADED)
    }

    /**
     * Simulates TRACKS_CHANGED event.
     */
    fun emitTracksChanged() {
        emitEvent(MpvNativeBridge.Event.TRACKS_CHANGED)
    }

    /**
     * Simulates END_FILE event.
     */
    fun emitEndFile() {
        emitEvent(MpvNativeBridge.Event.END_FILE)
    }

    /**
     * Simulates reason-bearing END_FILE event (`MpvEvent.EndFile(reason)`).
     */
    fun emitEndFile(reason: Int) {
        bus.publish(MpvEvent.EndFile(reason))
    }

    /**
     * Simulates SHUTDOWN event.
     */
    fun emitShutdown() {
        emitEvent(MpvNativeBridge.Event.SHUTDOWN)
    }

    /**
     * Emits a property name change notification (Format.NONE).
     */
    fun emitProperty(name: String) {
        bus.publish(MpvEvent.PropertyChanged(name))
    }

    /**
     * Emits a Long property change and updates the property store.
     */
    fun emitPropertyLong(name: String, value: Long) {
        properties[name] = value
        bus.publish(MpvEvent.LongProperty(name, value))
    }

    /**
     * Emits a Double property change and updates the property store.
     */
    fun emitPropertyDouble(name: String, value: Double) {
        properties[name] = value
        bus.publish(MpvEvent.DoubleProperty(name, value))
    }

    /**
     * Emits a Boolean property change and updates the property store.
     */
    fun emitPropertyFlag(name: String, value: Boolean) {
        properties[name] = value
        bus.publish(MpvEvent.FlagProperty(name, value))
    }

    /**
     * Emits a String property change and updates the property store.
     */
    fun emitPropertyString(name: String, value: String) {
        properties[name] = value
        bus.publish(MpvEvent.StringProperty(name, value))
    }

    // ---------- Query & Verification Helpers ----------

    /**
     * Returns true if any recorded command matches the provided prefix token sequence.
     */
    fun hasCommand(vararg prefixTokens: String): Boolean {
        return commands.any { cmd ->
            if (cmd.size < prefixTokens.size) false
            else prefixTokens.indices.all { i -> cmd[i] == prefixTokens[i] }
        }
    }

    /**
     * Returns the most recently dispatched command as a list of strings, or null if no command has run.
     */
    fun lastCommand(): List<String>? = commands.lastOrNull()

    /**
     * Finds all commands whose first argument equals [action].
     */
    fun findCommands(action: String): List<List<String>> {
        return commands.filter { it.firstOrNull() == action }
    }

    /**
     * Clears all recorded invocations, counts, and property state in a thread-safe manner.
     */
    @Synchronized
    fun reset() {
        _createCount.set(0)
        _initCount.set(0)
        _destroyCount.set(0)
        _attachSurfaceCount.set(0)
        _detachSurfaceCount.set(0)
        _redrawCount.set(0)
        commands.clear()
        options.clear()
        properties.clear()
        observedProperties.clear()
        attachedSurfaces.clear()
    }

    /**
     * Thread-safe property map backed by [ConcurrentHashMap] with support for nullable values
     * and safe concurrent iteration without [ConcurrentModificationException].
     */
    class ConcurrentPropertyMap(
        private val delegate: ConcurrentHashMap<String, Any> = ConcurrentHashMap()
    ) : MutableMap<String, Any?> {

        companion object {
            private val NULL_SENTINEL = Any()
        }

        override val size: Int
            get() = delegate.size

        override fun isEmpty(): Boolean = delegate.isEmpty()

        override fun containsKey(key: String): Boolean = delegate.containsKey(key)

        override fun containsValue(value: Any?): Boolean {
            val target = value ?: NULL_SENTINEL
            return delegate.containsValue(target)
        }

        override fun get(key: String): Any? {
            val v = delegate[key] ?: return null
            return if (v === NULL_SENTINEL) null else v
        }

        override fun put(key: String, value: Any?): Any? {
            val old = if (value == null) {
                delegate.put(key, NULL_SENTINEL)
            } else {
                delegate.put(key, value)
            }
            return if (old === NULL_SENTINEL) null else old
        }

        override fun remove(key: String): Any? {
            val old = delegate.remove(key) ?: return null
            return if (old === NULL_SENTINEL) null else old
        }

        override fun putAll(from: Map<out String, Any?>) {
            for ((k, v) in from) {
                put(k, v)
            }
        }

        override fun clear() {
            delegate.clear()
        }

        override val keys: MutableSet<String>
            get() = delegate.keys

        override val values: MutableCollection<Any?>
            get() = delegate.values.map { if (it === NULL_SENTINEL) null else it }.toMutableList()

        override val entries: MutableSet<MutableMap.MutableEntry<String, Any?>>
            get() = object : AbstractMutableSet<MutableMap.MutableEntry<String, Any?>>() {
                override val size: Int get() = delegate.size
                override fun iterator(): MutableIterator<MutableMap.MutableEntry<String, Any?>> {
                    val it = delegate.entries.iterator()
                    return object : MutableIterator<MutableMap.MutableEntry<String, Any?>> {
                        override fun hasNext(): Boolean = it.hasNext()
                        override fun next(): MutableMap.MutableEntry<String, Any?> = EntryWrapper(it.next())
                        override fun remove() = it.remove()
                    }
                }
                override fun add(element: MutableMap.MutableEntry<String, Any?>): Boolean =
                    throw UnsupportedOperationException()
            }

        private class EntryWrapper(
            private val delegateEntry: MutableMap.MutableEntry<String, Any>
        ) : MutableMap.MutableEntry<String, Any?> {
            override val key: String get() = delegateEntry.key
            override val value: Any?
                get() {
                    val v = delegateEntry.value
                    return if (v === NULL_SENTINEL) null else v
                }
            override fun setValue(newValue: Any?): Any? {
                val old = delegateEntry.setValue(newValue ?: NULL_SENTINEL)
                return if (old === NULL_SENTINEL) null else old
            }
        }
    }
}
