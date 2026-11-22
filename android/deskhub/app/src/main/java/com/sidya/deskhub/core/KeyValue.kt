package com.sidya.deskhub.core

import android.content.SharedPreferences

/** Minimal key/value storage so logic classes can be unit-tested without Android. */
interface KeyValue {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun getLong(key: String, def: Long = 0L): Long
    fun putLong(key: String, value: Long)
    fun getInt(key: String, def: Int = 0): Int
    fun putInt(key: String, value: Int)
    fun getBoolean(key: String, def: Boolean = false): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun remove(key: String)
}

class PrefsKeyValue(private val sp: SharedPreferences) : KeyValue {
    override fun getString(key: String): String? = sp.getString(key, null)
    override fun putString(key: String, value: String?) {
        sp.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
    override fun getLong(key: String, def: Long) = sp.getLong(key, def)
    override fun putLong(key: String, value: Long) = sp.edit().putLong(key, value).apply()
    override fun getInt(key: String, def: Int) = sp.getInt(key, def)
    override fun putInt(key: String, value: Int) = sp.edit().putInt(key, value).apply()
    override fun getBoolean(key: String, def: Boolean) = sp.getBoolean(key, def)
    override fun putBoolean(key: String, value: Boolean) = sp.edit().putBoolean(key, value).apply()
    override fun remove(key: String) = sp.edit().remove(key).apply()
}

class MemoryKeyValue : KeyValue {
    val map = HashMap<String, Any>()
    override fun getString(key: String) = map[key] as? String
    override fun putString(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    override fun getLong(key: String, def: Long) = map[key] as? Long ?: def
    override fun putLong(key: String, value: Long) { map[key] = value }
    override fun getInt(key: String, def: Int) = map[key] as? Int ?: def
    override fun putInt(key: String, value: Int) { map[key] = value }
    override fun getBoolean(key: String, def: Boolean) = map[key] as? Boolean ?: def
    override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
}

fun interface Clock { fun now(): Long }

val SystemClock = Clock { System.currentTimeMillis() }
