package com.example.spotifyplaylistmanager.data

import android.content.SharedPreferences

class FakeSharedPreferences : SharedPreferences {
    private val map = mutableMapOf<String, Any?>()

    override fun getString(key: String, defValue: String?): String? =
        map.getOrDefault(key, defValue) as? String ?: defValue

    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String, defValue: Int): Int = map.getOrDefault(key, defValue) as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = map.getOrDefault(key, defValue) as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = map.getOrDefault(key, defValue) as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = map.getOrDefault(key, defValue) as? Boolean ?: defValue
    override fun contains(key: String): Boolean = map.containsKey(key)
    override fun getAll(): MutableMap<String, *> = map
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removes = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor { pending[key] = values; return this }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { pending[key] = value; return this }
        override fun remove(key: String): SharedPreferences.Editor { removes.add(key); return this }
        override fun clear(): SharedPreferences.Editor { clearAll = true; return this }

        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clearAll) map.clear()
            removes.forEach { map.remove(it) }
            map.putAll(pending)
        }
    }
}
