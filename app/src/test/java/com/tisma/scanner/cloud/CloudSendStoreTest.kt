package com.tisma.scanner.cloud

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * El avance del envío (id de la subida y del trabajo) se escribe en el disco en el momento: con apply() queda para
 * más tarde, y si el sistema cierra la app justo después de crear la subida, el próximo intento sube todo de nuevo.
 */
@RunWith(RobolectricTestRunner::class)
class CloudSendStoreTest {

    /** Preferencias que anotan lo escrito en el disco: sólo commit() lo escribe en el momento. */
    private class DiskPrefs : SharedPreferences {
        val memory = mutableMapOf<String, String>()
        val disk = mutableMapOf<String, String>()
        var applies = 0

        override fun getString(key: String, defValue: String?) = memory[key] ?: defValue
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val puts = mutableMapOf<String, String?>()
            override fun putString(key: String, value: String?) = apply { puts[key] = value }
            override fun remove(key: String) = apply { puts[key] = null }
            private fun toMemory() = puts.forEach { (k, v) -> if (v == null) memory.remove(k) else memory[k] = v }
            override fun commit(): Boolean { toMemory(); disk.clear(); disk.putAll(memory); return true }
            override fun apply() { toMemory(); applies++ }   // al disco, más tarde
            override fun putStringSet(key: String, values: MutableSet<String>?) = this
            override fun putInt(key: String, value: Int) = this
            override fun putLong(key: String, value: Long) = this
            override fun putFloat(key: String, value: Float) = this
            override fun putBoolean(key: String, value: Boolean) = this
            override fun clear() = this
        }
        override fun getAll(): MutableMap<String, *> = memory
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String, defValue: Int) = defValue
        override fun getLong(key: String, defValue: Long) = defValue
        override fun getFloat(key: String, defValue: Float) = defValue
        override fun getBoolean(key: String, defValue: Boolean) = defValue
        override fun contains(key: String) = key in memory
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    @Test fun theUploadAndJobIdsAreOnDiskAtOnce() {
        val prefs = DiskPrefs()
        val store = CloudSendWorker.prefsStore(prefs)
        store.put("scan_a.zip_10", "up_1")
        assertEquals("up_1", prefs.disk["scan_a.zip_10"])         // si la app se cierra ahora, sigue ahí
        store.put("scan_a.zip_10.job", "job_1")
        store.put("scan_a.zip_10", null)
        assertEquals(mapOf("scan_a.zip_10.job" to "job_1"), prefs.disk)
        assertEquals("job_1", store.get("scan_a.zip_10.job"))
        assertNull(store.get("scan_a.zip_10"))
        assertTrue("nada queda para más tarde", prefs.applies == 0)
    }
}
