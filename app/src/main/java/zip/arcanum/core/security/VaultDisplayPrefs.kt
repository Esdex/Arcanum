package zip.arcanum.core.security

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.vaultDisplayDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "vault_display_prefs")

/**
 * How the vault list is arranged: the sort, its direction, the grouping, and whether vaults
 * with a fingerprint are pulled to the top.
 *
 * A class of its own rather than a delegate inside [zip.arcanum.arcanum.containers.ui.VaultViewModel]
 * because a second `preferencesDataStore` delegate for the same file crashes the process, and
 * settings backup has to read this one too.
 */
@Singleton
class VaultDisplayPrefs @Inject constructor(
    @ApplicationContext private val context: Context
) {
    object Keys {
        val SORT_BY         = stringPreferencesKey("sort_by")
        val SORT_DIRECTION  = stringPreferencesKey("sort_direction")
        val GROUP_BY        = stringPreferencesKey("group_by")
        val BIOMETRIC_FIRST = booleanPreferencesKey("biometric_first")
    }

    suspend fun read(): Preferences = context.vaultDisplayDataStore.data.first()

    suspend fun write(
        sortBy: String,
        direction: String,
        groupBy: String,
        biometricFirst: Boolean
    ) {
        context.vaultDisplayDataStore.edit { prefs ->
            prefs[Keys.SORT_BY]         = sortBy
            prefs[Keys.SORT_DIRECTION]  = direction
            prefs[Keys.GROUP_BY]        = groupBy
            prefs[Keys.BIOMETRIC_FIRST] = biometricFirst
        }
    }

    /** Everything in it, by key name - for the settings backup. */
    suspend fun exportAll(): Map<String, Any> =
        read().asMap().entries.associate { (key, value) -> key.name to value }

    suspend fun importAll(values: Map<String, Any>) {
        context.vaultDisplayDataStore.edit { prefs ->
            values.forEach { (name, value) ->
                when (value) {
                    is Boolean -> prefs[booleanPreferencesKey(name)] = value
                    is String  -> prefs[stringPreferencesKey(name)]  = value
                    else       -> Unit
                }
            }
        }
    }
}
