package zip.arcanum.arcanum.containers.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Eject
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.zIndex
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch
import zip.arcanum.R
import zip.arcanum.arcanum.containers.domain.Container
import zip.arcanum.core.icons.ArcanumIcons
import zip.arcanum.core.components.AppDialog
import zip.arcanum.core.notifications.InAppNotification
import zip.arcanum.core.notifications.InAppNotificationBanner
import zip.arcanum.core.components.AppSheet
import zip.arcanum.core.components.LocalHazeState
import zip.arcanum.core.components.SettingsSwitch
import zip.arcanum.core.database.entities.ContainerEntity
import zip.arcanum.core.theme.ArcanumHazeStyle
import zip.arcanum.core.theme.LocalAmoledMode
import zip.arcanum.core.theme.LocalDynamicColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultConfigScreen(
    containerId: String,
    viewModel: VaultViewModel,
    onBack: () -> Unit,
    onMount: (containerId: String) -> Unit,
    onOpenVault: (containerId: String) -> Unit,
    onChangePassword: (containerId: String) -> Unit,
    onChangeKeyfile: (containerId: String) -> Unit,
    onBackupHeader: (containerId: String) -> Unit,
    onRestoreHeader: (containerId: String) -> Unit,
    onMoveVault: (containerId: String, toApp: Boolean) -> Unit
) {
    val context      = LocalContext.current
    val isDynamic    = LocalDynamicColor.current
    val isAmoled     = LocalAmoledMode.current
    val containers   by viewModel.containers.collectAsState()
    val renameResult by viewModel.renameResult.collectAsState()
    val container    = containers.firstOrNull { it.id == containerId }
    val isMounted    = container?.isMounted ?: false
    val isUsbVaultHeader = container?.usbSaltHash?.isNotEmpty() == true

    val hazeState = remember { HazeState() }

    var showMoreMenu         by remember { mutableStateOf(false) }
    var showUsbMissing       by remember { mutableStateOf(false) }
    var showSafeToRemove     by remember { mutableStateOf(false) }
    val configScope          = rememberCoroutineScope()
    // What the user was trying to do when the drive turned out to be missing, so
    // "Try again" resumes it instead of just closing. One holder rather than a flag
    // per operation - every action that needs the volume goes through the same gate.
    var pendingUsbAction     by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Every operation that touches the volume passes through here: presence first,
    // then permission, and only then the action. Asking at this point rather than at
    // execution means the user learns the drive is missing before filling in a form,
    // and the system prompt cannot land on a half-typed password.
    val requireDrive: (() -> Unit) -> Unit = { action ->
        if (container?.usbSaltHash?.isNotEmpty() == true) {
            pendingUsbAction = action
            configScope.launch {
                if (viewModel.isUsbDriveAttached() && viewModel.ensureUsbPermission()) {
                    pendingUsbAction = null
                    action()
                } else {
                    showUsbMissing = true
                }
            }
        } else action()
    }

    var showRenameDialog     by remember { mutableStateOf(false) }
    var showMoveSheet        by remember { mutableStateOf(false) }
    var showAutoUnmountSheet by remember { mutableStateOf(false) }
    var showExternalAccessSheet by remember { mutableStateOf(false) }
    var showDeleteDialog     by remember { mutableStateOf(false) }
    var showForgetDialog     by remember { mutableStateOf(false) }
    var showUnmountDialog    by remember { mutableStateOf(false) }
    var renameText           by remember { mutableStateOf("") }
    var detailsContainer     by remember { mutableStateOf<Container?>(null) }
    var notification         by remember { mutableStateOf<InAppNotification?>(null) }
    val scope                = rememberCoroutineScope()

    LaunchedEffect(renameResult) {
        if (renameResult is VaultViewModel.RenameResult.Success) {
            showRenameDialog = false
            viewModel.clearRenameResult()
        }
    }


    val topBarColors  = if (isAmoled) TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                        else TopAppBarDefaults.topAppBarColors()
    val topBarHazeMod = if (isAmoled) Modifier.hazeEffect(state = hazeState, style = ArcanumHazeStyle.topBar)
                        else Modifier

    CompositionLocalProvider(LocalHazeState provides hazeState) {
        // The banner shares a Box with the Scaffold rather than living inside its content,
        // so it lands over the top bar instead of under it.
        Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    modifier        = topBarHazeMod,
                    colors          = topBarColors,
                    navigationIcon  = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                        }
                    },
                    title           = {
                        Text(
                            text     = stringResource(R.string.vault_config_title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    actions         = {
                        Box {
                            IconButton(onClick = { showMoreMenu = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded         = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text        = { Text(stringResource(R.string.vault_config_rename)) },
                                    leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null) },
                                    enabled     = !isMounted,
                                    onClick     = {
                                        showMoreMenu = false
                                        renameText   = container?.name ?: ""
                                        showRenameDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text        = { Text(stringResource(R.string.vault_config_move_sheet_title)) },
                                    leadingIcon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                                    enabled     = !isMounted,
                                    onClick     = {
                                        showMoreMenu  = false
                                        showMoveSheet = true
                                    }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text        = { Text(stringResource(R.string.vault_forget_confirm)) },
                                    leadingIcon = { Icon(Icons.Outlined.LinkOff, contentDescription = null) },
                                    enabled     = !isMounted,
                                    onClick     = {
                                        showMoreMenu     = false
                                        showForgetDialog = true
                                    }
                                )
                                // No Delete for a USB vault. There is no file to remove -
                                // the volume lives on a drive that is not the phone's - and
                                // deleteVaultFile matches neither a path nor a SAF document
                                // for one, so the red "delete forever" item did exactly what
                                // Forget does while promising to destroy the vault.
                                if (container?.usbSaltHash?.isNotEmpty() != true) {
                                    DropdownMenuItem(
                                        text        = { Text(stringResource(R.string.vault_delete_confirm), color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector        = Icons.Outlined.DeleteForever,
                                                contentDescription = null,
                                                tint               = MaterialTheme.colorScheme.error
                                            )
                                        },
                                        enabled     = !isMounted,
                                        onClick     = {
                                            showMoreMenu   = false
                                            showDeleteDialog = true
                                        }
                                    )
                                }
                            }
                        }
                    }
                )
            }
        ) { innerPadding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .hazeSource(hazeState)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(innerPadding)
                ) {
                    // ── Hero ──────────────────────────────────────────────────────
                    VaultConfigHero(
                        container = container,
                        isDynamic = isDynamic,
                        isMounted = isMounted,
                        onOpenDetails = {
                            scope.launch { detailsContainer = viewModel.getContainerDomain(containerId) }
                        },
                        onBlocked = { notification = InAppNotification.DetailsNeedMount }
                    )

                    // ── Operations ───────────────────────────────────────────────
                    VaultOperationItem(
                        icon      = if (isMounted) Icons.Outlined.FolderOpen else Icons.Outlined.PlayArrow,
                        rawColor  = Color(0xFF16A34A),
                        title     = stringResource(if (isMounted) R.string.vault_config_op_open else R.string.vault_config_op_mount),
                        subtitle  = stringResource(if (isMounted) R.string.vault_config_op_open_desc else R.string.vault_config_op_mount_desc),
                        isDynamic = isDynamic,
                        onClick   = {
                            when {
                                isMounted -> onOpenVault(containerId)
                                // A USB vault needs the drive before the password is worth
                                // asking for. Presence is checked passively; permission is
                                // requested here rather than mid-mount, so the system
                                // prompt does not interrupt someone typing a password.
                                else -> requireDrive { onMount(containerId) }
                            }
                        }
                    )
                    if (isMounted) {
                        // For a USB vault this IS the eject: closing the vault is what
                        // flushes and releases the drive. Offering both would imply there
                        // is a second step, and leaving it called "unmount" would hide the
                        // physical action the user still has to take.
                        VaultOperationItem(
                            icon      = Icons.Outlined.Eject,
                            rawColor  = Color(0xFF546E7A),
                            title     = stringResource(
                                if (isUsbVaultHeader) R.string.vault_config_op_eject
                                else R.string.vault_unmount_confirm
                            ),
                            subtitle  = stringResource(
                                if (isUsbVaultHeader) R.string.vault_config_op_eject_desc
                                else R.string.vault_card_unmount_desc
                            ),
                            isDynamic = isDynamic,
                            onClick   = { showUnmountDialog = true }
                        )
                    }
                    VaultOperationItem(
                        icon      = Icons.Outlined.Timer,
                        rawColor  = Color(0xFFD97706),
                        title     = stringResource(R.string.vault_config_op_auto_unmount),
                        subtitle  = stringResource(R.string.vault_config_op_auto_unmount_desc),
                        isDynamic = isDynamic,
                        onClick   = { showAutoUnmountSheet = true }
                    )
                    VaultOperationItem(
                        icon      = Icons.Outlined.Share,
                        rawColor  = Color(0xFF0E7490),
                        title     = stringResource(R.string.vault_config_op_external_access),
                        subtitle  = stringResource(R.string.vault_config_op_external_access_desc),
                        isDynamic = isDynamic,
                        onClick   = { showExternalAccessSheet = true }
                    )

                    VaultOperationItem(
                        icon      = Icons.Outlined.Key,
                        rawColor  = Color(0xFF1E88E5),
                        title     = stringResource(R.string.vault_config_change_password),
                        subtitle  = stringResource(if (isMounted) R.string.vault_config_unmount_first else R.string.chpwd_config_desc),
                        isDynamic = isDynamic,
                        enabled   = !isMounted,
                        onClick   = { requireDrive { onChangePassword(containerId) } }
                    )
                    VaultOperationItem(
                        icon      = ArcanumIcons.Keyfile,
                        rawColor  = Color(0xFF7B1FA2),
                        title     = stringResource(R.string.vault_config_change_keyfile),
                        subtitle  = stringResource(if (isMounted) R.string.vault_config_unmount_first else R.string.chkeyfile_config_desc),
                        isDynamic = isDynamic,
                        enabled   = !isMounted,
                        onClick   = { requireDrive { onChangeKeyfile(containerId) } }
                    )

                    VaultOperationItem(
                        icon      = Icons.Outlined.SaveAlt,
                        rawColor  = Color(0xFFE65100),
                        title     = stringResource(R.string.vault_info_op_backup_header),
                        subtitle  = stringResource(if (isMounted) R.string.vault_config_unmount_first else R.string.vault_card_backup_desc),
                        isDynamic = isDynamic,
                        enabled   = !isMounted,
                        onClick   = { requireDrive { onBackupHeader(containerId) } }
                    )
                    VaultOperationItem(
                        icon      = Icons.Outlined.Restore,
                        rawColor  = Color(0xFF00838F),
                        title     = stringResource(R.string.vault_info_op_restore_header),
                        subtitle  = stringResource(if (isMounted) R.string.vault_config_unmount_first else R.string.vault_card_restore_desc),
                        isDynamic = isDynamic,
                        enabled   = !isMounted,
                        onClick   = { requireDrive { onRestoreHeader(containerId) } }
                    )

                    Spacer(Modifier.navigationBarsPadding())
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        // ── Notification banner ───────────────────────────────────────────────
        InAppNotificationBanner(
            notification = notification,
            onDismiss    = { notification = null },
            onAction     = { notification = null },
            modifier     = Modifier.align(Alignment.TopCenter).statusBarsPadding().zIndex(20f)
        )
        }

        // ── Rename dialog ─────────────────────────────────────────────────────────
        // Modal rather than a passing banner: the user has a physical action to take, and
    // this vault kind is the one where missing it can cost data - the drive's own write
    // cache cannot be flushed on demand.
    if (showSafeToRemove) {
        AppDialog(
            onDismissRequest = { showSafeToRemove = false },
            title            = { Text(stringResource(R.string.notif_usb_safe_to_remove)) },
            text             = { Text(stringResource(R.string.usb_safe_to_remove_body)) },
            confirmButton    = {
                TextButton(onClick = { showSafeToRemove = false }) {
                    Text(stringResource(R.string.common_ok))
                }
            }
        )
    }

    if (showUsbMissing) {
        AppDialog(
            onDismissRequest = { showUsbMissing = false },
            title            = { Text(stringResource(R.string.usb_not_connected_title)) },
            text             = { Text(stringResource(R.string.usb_not_connected_body)) },
            confirmButton    = {
                TextButton(onClick = {
                    showUsbMissing = false
                    val retry = pendingUsbAction
                    configScope.launch {
                        if (viewModel.isUsbDriveAttached() && viewModel.ensureUsbPermission()) {
                            pendingUsbAction = null
                            retry?.invoke()
                        } else {
                            showUsbMissing = true
                        }
                    }
                }) { Text(stringResource(R.string.usb_try_again)) }
            },
            dismissButton    = {
                TextButton(onClick = { showUsbMissing = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (showRenameDialog) {
            AppDialog(
                onDismissRequest = { showRenameDialog = false },
                title            = { Text(stringResource(R.string.vault_rename_title)) },
                text             = {
                    OutlinedTextField(
                        value         = renameText,
                        onValueChange = { renameText = it },
                        label         = { Text(stringResource(R.string.vault_rename_label)) },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth()
                    )
                },
                confirmButton    = {
                    TextButton(
                        onClick  = { if (renameText.isNotBlank()) viewModel.renameContainer(containerId, renameText.trim()) },
                        enabled  = renameText.isNotBlank()
                    ) { Text(stringResource(R.string.vault_rename_confirm)) }
                },
                dismissButton    = {
                    TextButton(onClick = { showRenameDialog = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            )
        }

        // ── Unmount confirm dialog ────────────────────────────────────────────────
        if (showUnmountDialog && container != null) {
            val isUsb = container.usbSaltHash.isNotEmpty()
            AppDialog(
                onDismissRequest = { showUnmountDialog = false },
                title            = {
                    Text(stringResource(
                        if (isUsb) R.string.vault_eject_title else R.string.vault_unmount_title,
                        container.name
                    ))
                },
                text             = {
                    Text(stringResource(if (isUsb) R.string.vault_eject_body else R.string.vault_unmount_body))
                },
                confirmButton    = {
                    TextButton(onClick = {
                        showUnmountDialog = false
                        val name = container.name
                        viewModel.unmountContainer(containerId) {
                            if (isUsb) showSafeToRemove = true
                        }
                    }) {
                        Text(stringResource(if (isUsb) R.string.vault_eject_confirm else R.string.vault_unmount_confirm))
                    }
                },
                dismissButton    = {
                    TextButton(onClick = { showUnmountDialog = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            )
        }

        // ── Forget confirm dialog ─────────────────────────────────────────────────
        // Same words as the one on the vault list: it is the same action, and a user who
        // has read it once should not have to work out whether this one differs.
        if (showForgetDialog && container != null) {
            AppDialog(
                onDismissRequest = { showForgetDialog = false },
                title            = { Text(stringResource(R.string.vault_remove_title, container.name)) },
                text             = { Text(stringResource(R.string.vault_remove_body)) },
                confirmButton    = {
                    TextButton(onClick = {
                        showForgetDialog = false
                        viewModel.removeFromList(containerId)
                        onBack()
                    }) { Text(stringResource(R.string.vault_forget_confirm)) }
                },
                dismissButton    = {
                    TextButton(onClick = { showForgetDialog = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            )
        }

        // ── Delete confirm dialog ─────────────────────────────────────────────────
        if (showDeleteDialog && container != null) {
            AppDialog(
                onDismissRequest = { showDeleteDialog = false },
                title            = { Text(stringResource(R.string.vault_delete_title, container.name)) },
                text             = { Text(stringResource(R.string.vault_delete_body)) },
                confirmButton    = {
                    TextButton(onClick = {
                        showDeleteDialog = false
                        viewModel.deleteVaultFile(containerId)
                        onBack()
                    }) { Text(stringResource(R.string.vault_delete_confirm), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton    = {
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            )
        }

        // ── Move bottom sheet ─────────────────────────────────────────────────────
        if (showMoveSheet && container != null) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            AppSheet(
                onDismissRequest = { showMoveSheet = false },
                sheetState       = sheetState
            ) {
                Column(modifier = Modifier.padding(bottom = 32.dp)) {
                    Text(
                        text       = stringResource(R.string.vault_config_move_sheet_title),
                        style      = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier   = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                    val context2 = LocalContext.current
                    val isInAppStorage = container.safUri.isEmpty() &&
                        (container.path.startsWith(context2.filesDir.absolutePath) ||
                         container.path.startsWith(context2.noBackupFilesDir.absolutePath))

                    if (!isInAppStorage) {
                        androidx.compose.material3.ListItem(
                            headlineContent   = { Text(stringResource(R.string.vault_config_move_to_app)) },
                            supportingContent = { Text(stringResource(R.string.vault_config_move_to_app_desc), style = MaterialTheme.typography.bodySmall) },
                            trailingContent   = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) },
                            modifier          = Modifier.clickable {
                                showMoveSheet = false
                                onMoveVault(containerId, true)
                            }
                        )
                    }
                    androidx.compose.material3.ListItem(
                        headlineContent   = { Text(stringResource(R.string.vault_config_move_to_internal)) },
                        supportingContent = { Text(stringResource(R.string.vault_config_move_to_internal_desc), style = MaterialTheme.typography.bodySmall) },
                        trailingContent   = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) },
                        modifier          = Modifier.clickable {
                            showMoveSheet = false
                            onMoveVault(containerId, false)
                        }
                    )
                }
            }
        }

        // ── Auto-unmount bottom sheet ─────────────────────────────────────────────
        if (showAutoUnmountSheet && container != null) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            AppSheet(
                onDismissRequest = { showAutoUnmountSheet = false },
                sheetState       = sheetState
            ) {
                Column(modifier = Modifier.padding(bottom = 32.dp)) {
                    Text(
                        text       = stringResource(R.string.vault_config_auto_unmount_title),
                        style      = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier   = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                    SettingsSwitch(
                        title           = stringResource(R.string.vault_config_unmount_on_lock),
                        subtitle        = stringResource(R.string.vault_config_unmount_on_lock_desc),
                        checked         = container.unmountOnLock,
                        onCheckedChange = { viewModel.updateUnmountOnLock(containerId, it) }
                    )
                    SettingsSwitch(
                        title           = stringResource(R.string.vault_config_unmount_on_background),
                        subtitle        = stringResource(R.string.vault_config_unmount_on_background_desc),
                        checked         = container.unmountOnBackground,
                        onCheckedChange = { viewModel.updateUnmountOnBackground(containerId, it) }
                    )
                }
            }
        }

        // ── External app access bottom sheet ──────────────────────────────────────
        if (showExternalAccessSheet && container != null) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            AppSheet(
                onDismissRequest = { showExternalAccessSheet = false },
                sheetState       = sheetState
            ) {
                Column(modifier = Modifier.padding(bottom = 32.dp)) {
                    Text(
                        text       = stringResource(R.string.vault_config_external_access_title),
                        style      = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier   = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                    Text(
                        text     = stringResource(R.string.vault_config_external_access_warning),
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    SettingsSwitch(
                        title           = stringResource(R.string.vault_config_external_access_switch),
                        subtitle        = stringResource(R.string.vault_config_external_access_switch_desc),
                        checked         = container.externalAccessEnabled,
                        onCheckedChange = { viewModel.updateExternalAccessEnabled(containerId, it) }
                    )
                }
            }
        }

        // ── Vault details sheet (General + Encryption) ────────────────────────────
        detailsContainer?.let { details ->
            VaultDetailsSheet(
                container = details,
                onDismiss = { detailsContainer = null }
            )
        }
    }
}

// ── Hero ──────────────────────────────────────────────────────────────────────

@Composable
private fun VaultConfigHero(
    container: ContainerEntity?,
    isDynamic: Boolean,
    isMounted: Boolean = false,
    onOpenDetails: () -> Unit = {},
    onBlocked: () -> Unit = {}
) {
    val context = LocalContext.current
    val isUsbVault = container?.usbSaltHash?.isNotEmpty() == true
    val heroIcon = vaultStorageIcon(
        path        = container?.path ?: "",
        safUri      = container?.safUri ?: "",
        usbSaltHash = container?.usbSaltHash ?: ""
    )

    val iconBg by animateColorAsState(
        targetValue   = if (isMounted) Color(0xFF16A34A) else MaterialTheme.colorScheme.primaryContainer,
        animationSpec = tween(300),
        label         = "hero_bg"
    )
    val iconTint by animateColorAsState(
        targetValue   = if (isMounted) Color.White else MaterialTheme.colorScheme.primary,
        animationSpec = tween(300),
        label         = "hero_tint"
    )

    val hop     = remember { Animatable(0f) }
    val shake   = remember { Animatable(0f) }
    var shakeTrigger by remember { mutableIntStateOf(0) }
    val haptics = LocalHapticFeedback.current

    // Only while mounted: a hop on a vault that cannot show details would be inviting a
    // press that ends in a refusal.
    LaunchedEffect(isMounted) {
        if (!isMounted) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(4000)
            hop.animateTo(-26f, tween(200, easing = LinearOutSlowInEasing))
            hop.animateTo(0f, spring(dampingRatio = 0.3f, stiffness = 700f))
        }
    }

    LaunchedEffect(shakeTrigger) {
        if (shakeTrigger == 0) return@LaunchedEffect
        repeat(3) {
            shake.animateTo(12f, tween(50))
            shake.animateTo(-12f, tween(50))
        }
        shake.animateTo(0f, tween(50))
    }

    val displayPath = remember(container?.path, container?.safUri, container?.name) {
        when {
            container == null -> ""
            container.path.isNotBlank() -> {
                val path = container.path
                val appDataDir = context.filesDir.parentFile?.absolutePath ?: ""
                when {
                    path.startsWith(context.filesDir.absolutePath) ||
                    path.startsWith(context.noBackupFilesDir.absolutePath) -> {
                        val relative = if (appDataDir.isNotEmpty())
                            path.removePrefix(appDataDir).trimStart('/')
                        else path
                        "App Storage/$relative"
                    }
                    path.startsWith("/storage/emulated/0/") ->
                        "Internal/" + path.removePrefix("/storage/emulated/0/")
                    path.startsWith("/sdcard/") ->
                        "Internal/" + path.removePrefix("/sdcard/")
                    else -> path
                }
            }
            container.safUri.isNotBlank() -> safUriLocationDisplay(container.safUri, container.name)
            else -> ""
        }
    }

    Column(
        modifier            = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // The icon is the way into the details, the way the lock is the way in on the
        // mount screen. It only invites a press while the vault is open, because that is
        // the only time it has anything to show - the algorithm, key size and PIM all
        // come from a header that is not readable until then.
        Box(
            modifier         = Modifier
                .offset { IntOffset(shake.value.roundToInt(), hop.value.roundToInt()) }
                .size(96.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(iconBg)
                .clickable {
                    if (isMounted) {
                        onOpenDetails()
                    } else {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onBlocked()
                        shakeTrigger++
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector        = heroIcon,
                contentDescription = null,
                tint               = iconTint,
                modifier           = Modifier.size(48.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text       = container?.name ?: "",
            style      = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        if (displayPath.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text     = displayPath,
                style    = MaterialTheme.typography.bodySmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }
    }
}

// ── Operation list item ───────────────────────────────────────────────────────

@Composable
private fun VaultOperationItem(
    icon     : ImageVector,
    rawColor : Color,
    title    : String,
    subtitle : String,
    isDynamic: Boolean,
    enabled  : Boolean = true,
    onClick  : () -> Unit
) {
    val iconColor = if (isDynamic) MaterialTheme.colorScheme.primary else rawColor
    val effectiveIconColor = if (enabled) iconColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val effectiveTitleColor = if (enabled) MaterialTheme.colorScheme.onSurface
                              else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

    ListItem(
        colors          = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent  = {
            Box(
                modifier         = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(effectiveIconColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector        = icon,
                    contentDescription = null,
                    tint               = effectiveIconColor,
                    modifier           = Modifier.size(20.dp)
                )
            }
        },
        headlineContent  = {
            Text(
                text  = title,
                style = MaterialTheme.typography.bodyLarge,
                color = effectiveTitleColor
            )
        },
        supportingContent = {
            Text(
                text  = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        modifier         = Modifier
            .padding(horizontal = 8.dp)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
    )
}
