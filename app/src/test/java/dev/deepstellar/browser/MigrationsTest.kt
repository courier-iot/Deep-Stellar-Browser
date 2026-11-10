package dev.deepstellar.browser

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** A plain in-memory SharedPreferences, enough for testing settings upgrades. */
class FakePrefs : SharedPreferences {
    val map = HashMap<String, Any?>()
    override fun getAll(): MutableMap<String, *> = HashMap(map)
    override fun getString(k: String, d: String?) = map[k] as? String ?: d
    override fun getStringSet(k: String, d: MutableSet<String>?): MutableSet<String>? {
        @Suppress("UNCHECKED_CAST") return (map[k] as? MutableSet<String>) ?: d
    }
    override fun getInt(k: String, d: Int) = if (map.containsKey(k)) map[k] as Int else d
    override fun getLong(k: String, d: Long) = map[k] as? Long ?: d
    override fun getFloat(k: String, d: Float) = map[k] as? Float ?: d
    override fun getBoolean(k: String, d: Boolean) = map[k] as? Boolean ?: d
    override fun contains(k: String) = map.containsKey(k)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        val pending = HashMap<String, Any?>()
        val removed = HashSet<String>()
        var clear = false
        override fun putString(k: String, v: String?) = apply { pending[k] = v }
        override fun putStringSet(k: String, v: MutableSet<String>?) = apply { pending[k] = v }
        override fun putInt(k: String, v: Int) = apply { pending[k] = v }
        override fun putLong(k: String, v: Long) = apply { pending[k] = v }
        override fun putFloat(k: String, v: Float) = apply { pending[k] = v }
        override fun putBoolean(k: String, v: Boolean) = apply { pending[k] = v }
        override fun remove(k: String) = apply { removed += k }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clear) map.clear()
            removed.forEach { map.remove(it) }
            map.putAll(pending)
        }
    }
}

class MigrationsTest {
    @Test fun upgradesOldSettings() {
        val sp = FakePrefs()
        sp.map["keep_sites"] = mutableSetOf("example.com")
        sp.map["tab_limit_index"] = 42
        sp.map["cpu_mode"] = 2
        Migrations.run(sp)
        assertFalse(sp.contains("keep_sites"))
        assertEquals(3, sp.getInt("tab_limit_index", -1))
        assertEquals(2, sp.getInt("cpu_mode", -1)) // valid values are left alone
        assertEquals(Migrations.CURRENT, sp.getInt("settings_version", 0))
    }

    @Test fun runsOnlyOnce() {
        val sp = FakePrefs()
        Migrations.run(sp)
        sp.map["tab_limit_index"] = 99 // a later bad value is not "fixed" again
        Migrations.run(sp)
        assertEquals(99, sp.getInt("tab_limit_index", -1))
    }
}
