package zip.arcanum.core.navigation

sealed class Screen(val route: String) {
    object Calculator      : Screen("calculator")
    object DisguiseUnlock  : Screen("disguise_unlock")
    object PinEntry        : Screen("pin_entry")
    object SetupPin        : Screen("setup_pin")
    object Onboarding      : Screen("onboarding")
    object VaultScreen     : Screen("vault_screen")
    object CreateContainer : Screen("create_container")
    object AppSettings     : Screen("app_settings")

    object ContainerScreen : Screen("container/{containerId}") {
        const val ARG = "containerId"
        fun buildRoute(id: String) = "container/$id"
    }

    object Gallery : Screen("gallery/{containerId}") {
        const val ARG = "containerId"
        fun buildRoute(containerId: String) = "gallery/$containerId"
    }

    object PhotoViewer : Screen("photo_viewer/{photoId}") {
        const val ARG = "photoId"
        fun buildRoute(photoId: String) = "photo_viewer/$photoId"
    }

    object VideoPlayer : Screen("video_player/{fileId}") {
        const val ARG = "fileId"
        fun buildRoute(fileId: String) = "video_player/$fileId"
    }

    object AudioPlayer : Screen("audio_player/{fileId}") {
        const val ARG = "fileId"
        fun buildRoute(fileId: String) = "audio_player/$fileId"
    }

    object MediaViewerDirect : Screen("media_viewer_direct?cid={cid}&path={path}&name={name}&size={size}") {
        const val ARG_CONTAINER = "cid"
        const val ARG_PATH      = "path"
        const val ARG_NAME      = "name"
        const val ARG_SIZE      = "size"

        fun buildRoute(containerId: String, path: String, name: String, size: Long): String {
            val encodedPath = android.net.Uri.encode(path)
            val encodedName = android.net.Uri.encode(name)
            return "media_viewer_direct?cid=$containerId&path=$encodedPath&name=$encodedName&size=$size"
        }
    }

    object AudioPlayerDirect : Screen("audio_player_direct?cid={cid}&path={path}&name={name}&size={size}") {
        const val ARG_CONTAINER = "cid"
        const val ARG_PATH      = "path"
        const val ARG_NAME      = "name"
        const val ARG_SIZE      = "size"

        fun buildRoute(containerId: String, path: String, name: String, size: Long): String {
            val encodedPath = android.net.Uri.encode(path)
            val encodedName = android.net.Uri.encode(name)
            // size passed as StringType to avoid LongType/Int coercion bugs in SavedStateHandle
            return "audio_player_direct?cid=$containerId&path=$encodedPath&name=$encodedName&size=$size"
        }
    }

    object FileManager : Screen("file_manager/{containerId}") {
        const val ARG = "containerId"
        fun buildRoute(containerId: String) = "file_manager/$containerId"
    }

    object TextEditor : Screen("text_editor?cid={cid}&path={path}&name={name}") {
        const val ARG_CONTAINER = "cid"
        const val ARG_PATH      = "path"
        const val ARG_NAME      = "name"

        fun buildRoute(containerId: String, path: String, name: String): String {
            val encodedPath = android.net.Uri.encode(path)
            val encodedName = android.net.Uri.encode(name)
            return "text_editor?cid=$containerId&path=$encodedPath&name=$encodedName"
        }
    }

    object MoveVault : Screen("move_vault/{containerId}/{toApp}") {
        const val ARG_ID    = "containerId"
        const val ARG_TO_APP = "toApp"
        fun buildRoute(containerId: String, toApp: Boolean) = "move_vault/$containerId/$toApp"
    }

    object MountScreen : Screen("mount_screen/{containerId}?enableBio={enableBio}") {
        const val ARG = "containerId"
        const val ARG_ENABLE_BIO = "enableBio"
        fun buildRoute(containerId: String, enableBiometricSetup: Boolean = false) =
            if (enableBiometricSetup) "mount_screen/$containerId?enableBio=true" else "mount_screen/$containerId"
    }

    object WhatsNew : Screen("whats_new")

    object ChangePassword : Screen("change_password/{containerId}") {
        const val ARG = "containerId"
        fun buildRoute(containerId: String) = "change_password/$containerId"
    }

    object ChangeKeyfile : Screen("change_keyfile/{containerId}") {
        const val ARG = "containerId"
        fun buildRoute(containerId: String) = "change_keyfile/$containerId"
    }

    object VaultConfig : Screen("vault_config/{containerId}") {
        const val ARG = "containerId"
        fun buildRoute(containerId: String) = "vault_config/$containerId"
    }

    object Backup : Screen("backup/{containerId}") {
        const val ARG = "containerId"
        fun buildRoute(containerId: String) = "backup/$containerId"
    }

    object ExpandVolume : Screen("expand_volume/{containerId}") {
        const val ARG = "containerId"
        fun buildRoute(containerId: String) = "expand_volume/$containerId"
    }

    object BackupHeader : Screen("backup_header/{containerId}") {
        const val ARG = "containerId"
        fun buildRoute(containerId: String) = "backup_header/$containerId"
    }

    object RestoreHeader : Screen("restore_header/{containerId}") {
        const val ARG = "containerId"
        fun buildRoute(containerId: String) = "restore_header/$containerId"
    }
}
