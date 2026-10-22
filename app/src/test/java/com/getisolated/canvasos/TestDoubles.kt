package com.getisolated.canvasos

import android.content.ContextWrapper
import android.content.SharedPreferences

/**
 * Test doubles that let Prefs-backed logic run on the JVM: an in-memory SharedPreferences and a
 * Context that hands it out. Nothing else of Context works (it throws "not mocked"), which is the
 * point: a test that needs more should say so.
 */
class MemorySharedPreferences : SharedPreferences {
    private val map = LinkedHashMap<String, Any>()

    /** The raw stored value, for asserting what was written. */
    operator fun get(key: String): Any? = map[key]

    val keys: Set<String> get() = map.keys.toSet()

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(map)
    override fun getString(key: String, defValue: String?): String? = map[key] as String? ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (map[key] as Set<String>?)?.let { HashSet(it) } ?: defValues

    override fun getInt(key: String, defValue: Int): Int = map[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long): Long = map[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = map[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as Boolean? ?: defValue
    override fun contains(key: String): Boolean = key in map
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private var clear = false

        // Like the real thing, a null value removes the key.
        private fun put(key: String, value: Any?) = apply { pending[key] = value }

        override fun putString(key: String, value: String?) = put(key, value)
        override fun putStringSet(key: String, values: MutableSet<String>?) = put(key, values?.let { HashSet(it) })
        override fun putInt(key: String, value: Int) = put(key, value)
        override fun putLong(key: String, value: Long) = put(key, value)
        override fun putFloat(key: String, value: Float) = put(key, value)
        override fun putBoolean(key: String, value: Boolean) = put(key, value)
        override fun remove(key: String) = put(key, null)
        override fun clear() = apply { clear = true }

        override fun commit(): Boolean {
            if (clear) map.clear()
            for ((k, v) in pending) if (v == null) map.remove(k) else map[k] = v
            pending.clear()
            clear = false
            return true
        }

        override fun apply() { commit() }
    }
}

/** A Context whose only working part is [getSharedPreferences] (and [getApplicationContext], itself). */
class FakeContext(val store: MemorySharedPreferences = MemorySharedPreferences()) : ContextWrapper(null) {
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = store
    override fun getApplicationContext() = this
}
