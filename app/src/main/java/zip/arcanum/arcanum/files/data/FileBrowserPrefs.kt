package zip.arcanum.arcanum.files.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * The Files browser's view settings.
 *
 * They live here rather than inside FileManagerViewModel because a second screen needs to
 * read them: swiping between photos should follow the order of the list the photos were
 * opened from, and the only way two screens agree on an order is to read the same setting.
 * The viewer used to sort by nothing at all - it took whatever order the media index came
 * back in - so the swipe ignored the sort the user had chosen (#151).
 *
 * A DataStore may exist only once per file per process, so this delegate is the single
 * definition; nothing else may declare "file_manager_prefs".
 */
internal val Context.fileManagerPrefs: DataStore<Preferences> by preferencesDataStore("file_manager_prefs")

/** How the browser is sorting, as stored. The name is a FileManagerViewModel.SortBy entry. */
data class BrowserSort(val by: String, val ascending: Boolean)

object FileBrowserPrefs {
    val SORT_BY_KEY       = stringPreferencesKey("sort_by")
    val SORT_ASC_KEY      = booleanPreferencesKey("sort_asc")
    val SHOW_HIDDEN_KEY   = booleanPreferencesKey("show_hidden")
    val FOLDERS_FIRST_KEY = booleanPreferencesKey("folders_first")
    val VIEW_MODE_KEY     = stringPreferencesKey("view_mode")

    /** The defaults here must match FileManagerViewModel's, or the two screens disagree. */
    suspend fun sortOnce(context: Context): BrowserSort {
        val p = context.fileManagerPrefs.data.first()
        return BrowserSort(p[SORT_BY_KEY] ?: "DATE", p[SORT_ASC_KEY] ?: false)
    }
}
