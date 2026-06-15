package zip.arcanum.core.notifications

sealed class InAppNotification {
    abstract val priority: Int
    open val bannerKey: String get() = this::class.simpleName ?: ""

    data class VaultMounted(
        val vaultId: String,
        val vaultName: String
    ) : InAppNotification() {
        override val priority = 1
    }

    data class VaultUnmounted(
        val vaultId: String,
        val vaultName: String
    ) : InAppNotification() {
        override val priority = 1
    }

    data class VaultError(
        val vaultId: String,
        val message: String
    ) : InAppNotification() {
        override val priority = 2
    }

    data class ExportSuccess(
        val fileName: String
    ) : InAppNotification() {
        override val priority = 1
    }

    data class VaultAdded(
        val fileName: String
    ) : InAppNotification() {
        override val priority = 1
    }

    data class VaultAlreadyExists(
        val fileName: String
    ) : InAppNotification() {
        override val priority = 2
    }

    data object VaultInvalidFile : InAppNotification() {
        override val priority = 2
    }

    data class VaultAddError(
        val message: String
    ) : InAppNotification() {
        override val priority = 2
    }

    data object PanicExecuted : InAppNotification() {
        override val priority = 0
    }

    data object SupportDeveloper : InAppNotification() {
        override val priority = 4
    }

    data object DateUpdated : InAppNotification() {
        override val priority = 1
    }

    data class FileRenamed(val newName: String) : InAppNotification() {
        override val priority = 1
    }

    data class FilesPasted(val count: Int) : InAppNotification() {
        override val priority = 1
    }

    data class FilesMoved(val count: Int, val destinationName: String) : InAppNotification() {
        override val priority = 1
    }

    data class FilesDeleted(val count: Int) : InAppNotification() {
        override val priority = 1
    }

    data class FolderCreated(val name: String) : InAppNotification() {
        override val priority = 1
    }

    data class FilesImported(val count: Int) : InAppNotification() {
        override val priority = 1
    }

    data class FilesExported(val count: Int) : InAppNotification() {
        override val priority = 1
    }
}
