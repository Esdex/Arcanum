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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.LargeTopAppBar
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import zip.arcanum.core.components.BackButton
import zip.arcanum.core.components.rememberCollapsedLargeTopBarBehavior
import zip.arcanum.core.components.GroupedRow
import zip.arcanum.core.components.SettingsGroup
import zip.arcanum.core.notifications.InAppNotification
import zip.arcanum.core.notifications.LocalNotifications
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
    val notifications        = LocalNotifications.current
    val scope                = rememberCoroutineScope()

    LaunchedEffect(renameResult) {
        if (renameResult is VaultViewModel.RenameResult.Success) {
            showRenameDialog = false
            viewModel.clearRenameResult()
        }
    }


    // Big title on the left that shrinks into an ordinary bar as the page moves under it,
    // with the back arrow in a circle of its own - the shape of Android's own App info.
    val scrollBehavior = rememberCollapsedLargeTopBarBehavior()
    val topBarColors  = if (isAmoled) TopAppBarDefaults.largeTopAppBarColors(
                            containerColor = Color.Transparent,
                            scrolledContainerColor = Color.Transparent
                        )
                        else TopAppBarDefaults.largeTopAppBarColors()
    val topBarHazeMod = if (isAmoled) Modifier.hazeEffect(state = hazeState, style = ArcanumHazeStyle.topBar)
                        else Modifier

    CompositionLocalProvider(LocalHazeState provides hazeState) {
        // The banner shares a Box with the Scaffold rather than living inside its content,
        // so it lands over the top bar instead of under it.
        Box(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                LargeTopAppBar(
                    modifier        = topBarHazeMod,
                    colors          = topBarColors,
                    scrollBehavior  = scrollBehavior,
                    navigationIcon  = {
                        BackButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp))
                    },
                    title           = {
                        Text(
                            text     = stringResource(R.string.vault_config_title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    actions         = {
                        // Details sit behind a button now rather than behind a tap on the
                        // vault's icon: a picture that opens a screen is something you have
                        // to be told about. It is grey while the vault is closed, because
                        // the header it reads cannot be decrypted until then - and it says
                        // so when pressed rather than doing nothing at all.
                        IconButton(
                            onClick = {
                                if (isMounted) {
                                    scope.launch {
                                        detailsContainer = viewModel.getContainerDomain(containerId)
                                    }
                                } else notifications.notify(InAppNotification.DetailsNeedMount)
                            }
                        ) {
                            Icon(
                                imageVector        = Icons.Outlined.Info,
                                contentDescription = stringResource(R.string.vault_config_cd_info),
                                tint               = if (isMounted) MaterialTheme.colorScheme.onSurface
                                                     else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
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
                    VaultConfigHero(container = container, isMounted = isMounted)

                    // ── The three actions ────────────────────────────────────────
                    // Mount and unmount are one button, on the right, because they are one
                    // decision with two states - and for a drive, unmounting IS the eject.
                    Row(
                        modifier              = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                    ) {
                        VaultActionCapsule(
                            icon    = Icons.Outlined.FolderOpen,
                            label   = stringResource(R.string.vault_config_op_open),
                            enabled = isMounted,
                            onClick = { onOpenVault(containerId) }
                        )
                        VaultActionCapsule(
                            icon    = Icons.Outlined.DriveFileRenameOutline,
                            label   = stringResource(R.string.vault_config_rename),
                            enabled = !isMounted,
                            onClick = {
                                renameText = container?.name ?: ""
                                showRenameDialog = true
                            }
                        )
                        VaultActionCapsule(
                            icon    = when {
                                !isMounted        -> Icons.Outlined.PlayArrow
                                isUsbVaultHeader  -> Icons.Outlined.Eject
                                else              -> Icons.Outlined.Lock
                            },
                            label   = stringResource(
                                when {
                                    !isMounted       -> R.string.vault_config_op_mount
                                    isUsbVaultHeader -> R.string.vault_config_op_eject
                                    else             -> R.string.vault_unmount_confirm
                                }
                            ),
                            emphasised = true,
                            onClick = {
                                if (isMounted) showUnmountDialog = true
                                else requireDrive { onMount(containerId) }
                            }
                        )
                    }

                    // ── Access ───────────────────────────────────────────────────
                    SettingsGroup(title = stringResource(R.string.vault_config_group_access)) {
                        row { shape ->
                            GroupedRow(
                                shape    = shape,
                                title    = stringResource(R.string.vault_config_change_password),
                                subtitle = stringResource(
                                    if (isMounted) R.string.vault_config_unmount_first
                                    else R.string.chpwd_config_desc
                                ),
                                enabled  = !isMounted,
                                onClick  = { requireDrive { onChangePassword(containerId) } }
                            )
                        }
                        row { shape ->
                            GroupedRow(
                                shape    = shape,
                                title    = stringResource(R.string.vault_config_change_keyfile),
                                subtitle = stringResource(
                                    if (isMounted) R.string.vault_config_unmount_first
                                    else R.string.chkeyfile_config_desc
                                ),
                                enabled  = !isMounted,
                                onClick  = { requireDrive { onChangeKeyfile(containerId) } }
                            )
                        }
                        row { shape ->
                            GroupedRow(
                                shape    = shape,
                                title    = stringResource(R.string.vault_config_op_external_access),
                                subtitle = stringResource(R.string.vault_config_op_external_access_desc),
                                onClick  = { showExternalAccessSheet = true }
                            )
                        }
                    }

                    // ── Protection ───────────────────────────────────────────────
                    SettingsGroup(title = stringResource(R.string.vault_config_group_protection)) {
                        row { shape ->
                            GroupedRow(
                                shape    = shape,
                                title    = stringResource(R.string.vault_config_op_auto_unmount),
                                subtitle = stringResource(R.string.vault_config_op_auto_unmount_desc),
                                onClick  = { showAutoUnmountSheet = true }
                            )
                        }
                        row { shape ->
                            GroupedRow(
                                shape    = shape,
                                title    = stringResource(R.string.vault_info_op_backup_header),
                                subtitle = stringResource(
                                    if (isMounted) R.string.vault_config_unmount_first
                                    else R.string.vault_card_backup_desc
                                ),
                                enabled  = !isMounted,
                                onClick  = { requireDrive { onBackupHeader(containerId) } }
                            )
                        }
                        row { shape ->
                            GroupedRow(
                                shape    = shape,
                                title    = stringResource(R.string.vault_info_op_restore_header),
                                subtitle = stringResource(
                                    if (isMounted) R.string.vault_config_unmount_first
                                    else R.string.vault_card_restore_desc
                                ),
                                enabled  = !isMounted,
                                onClick  = { requireDrive { onRestoreHeader(containerId) } }
                            )
                        }
                    }

                    // ── Manage ───────────────────────────────────────────────────
                    // What used to hide behind the three dots. Delete is missing for a USB
                    // vault on purpose: there is no file of ours to remove, and the red
                    // "delete forever" only ever did what Forget does.
                    SettingsGroup(title = stringResource(R.string.vault_config_group_manage)) {
                        row { shape ->
                            GroupedRow(
                                shape    = shape,
                                title    = stringResource(R.string.vault_config_move_sheet_title),
                                subtitle = stringResource(R.string.vault_config_move_desc),
                                enabled  = !isMounted,
                                onClick  = { showMoveSheet = true }
                            )
                        }
                        row { shape ->
                            GroupedRow(
                                shape    = shape,
                                title    = stringResource(R.string.vault_forget_confirm),
                                subtitle = stringResource(R.string.vault_config_forget_desc),
                                enabled  = !isMounted,
                                onClick  = { showForgetDialog = true }
                            )
                        }
                        if (!isUsbVaultHeader) {
                            row { shape ->
                                GroupedRow(
                                    shape      = shape,
                                    title      = stringResource(R.string.vault_delete_confirm),
                                    subtitle   = stringResource(R.string.vault_config_delete_desc),
                                    enabled    = !isMounted,
                                    titleColor = MaterialTheme.colorScheme.error,
                                    onClick    = { showDeleteDialog = true }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                    Spacer(Modifier.navigationBarsPadding())
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

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
    isMounted: Boolean = false
) {
    val context = LocalContext.current
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

    // Nothing to press here any more: the details moved to the button in the top bar, and
    // with them went the hop that used to invite the press and the shake that answered one
    // the vault could not honour.
    Column(
        modifier            = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier         = Modifier
                .size(96.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(iconBg),
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
            style      = MaterialTheme.typography.headlineSmall,
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

// ── The round actions under the vault's name ─────────────────────────────────

/**
 * One of the three actions at the top, shaped after the ones on Android's own App info
 * screen: a wide rounded blob with the icon inside it and the word underneath.
 */
@Composable
private fun VaultActionCapsule(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    emphasised: Boolean = false,
    onClick: () -> Unit
) {
    val container = when {
        !enabled   -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f)
        emphasised -> MaterialTheme.colorScheme.primaryContainer
        else       -> MaterialTheme.colorScheme.secondaryContainer
    }
    val content = when {
        !enabled   -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        emphasised -> MaterialTheme.colorScheme.onPrimaryContainer
        else       -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .width(104.dp)
                .height(60.dp)
                .clip(RoundedCornerShape(30.dp))
                .background(container)
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = content,
                modifier           = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text       = label,
            style      = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color      = if (enabled) MaterialTheme.colorScheme.onSurface
                         else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        )
    }
}
