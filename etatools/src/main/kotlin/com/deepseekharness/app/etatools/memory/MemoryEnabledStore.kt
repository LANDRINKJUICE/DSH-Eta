package com.deepseekharness.app.etatools.memory

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

object MemoryEnabledStore {
    private val MEMORY_ENABLED = booleanPreferencesKey("memory_enabled")
    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "eta_memory_settings")

    fun enabledFlow(context: Context): Flow<Boolean> = context.dataStore.data.map { it[MEMORY_ENABLED] ?: true }

    suspend fun isEnabled(context: Context): Boolean = context.dataStore.data.first()[MEMORY_ENABLED] ?: true

    suspend fun setEnabled(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[MEMORY_ENABLED] = enabled }
    }
}
