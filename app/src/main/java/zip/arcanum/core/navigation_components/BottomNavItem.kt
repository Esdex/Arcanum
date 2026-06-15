package zip.arcanum.core.navigation_components

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.ui.graphics.vector.ImageVector
import zip.arcanum.R

sealed class BottomNavItem(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector
) {
    object Containers  : BottomNavItem("containers", R.string.nav_containers, Icons.Outlined.Lock)
    object Gallery     : BottomNavItem("gallery",    R.string.nav_gallery,    Icons.Outlined.PhotoLibrary)
    object Files       : BottomNavItem("files",      R.string.nav_files,      Icons.Outlined.Folder)
    object AppSettings : BottomNavItem("settings",   R.string.nav_settings,   Icons.Outlined.Settings)

    // Container-screen tabs (shown when inside a mounted container)
    object ContainerGallery : BottomNavItem("ct_gallery", R.string.nav_gallery,  Icons.Outlined.PhotoLibrary)
    object ContainerFiles   : BottomNavItem("ct_files",   R.string.nav_files,    Icons.Outlined.Folder)
    object ContainerInfo    : BottomNavItem("ct_info",    R.string.nav_vault_fallback_name, Icons.Outlined.Storage)
}
