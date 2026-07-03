package zip.arcanum.arcanum.containers.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import zip.arcanum.R
import zip.arcanum.core.components.AppDialog
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
    onMount: (containerId: String, enableBiometricSetup: Boolean) -> Unit,
    onOpenVault: (containerId: String) -> Unit,
    onUnmountVault: (containerId: String) -> Unit,
    onChangePassword: (containerId: String) -> Unit,
    onChangeKeyfile: (containerId: String) -> Unit,
    onMoveVault: (containerId: String, toApp: Boolean) -> Unit,
    onBackup: (containerId: String) -> Unit,
    onExpandVolume: (containerId: String) -> Unit
) {
    val isDynamic    = LocalDynamicColor.current
    val isAmoled     = LocalAmoledMode.current
    val containers   by viewModel.containers.collectAsState()
    val renameResult by viewModel.renameResult.collectAsState()
    val biometricUnlockEnabled by viewModel.biometricUnlockEnabled.collectAsState()
    val container    = containers.firstOrNull { it.id == containerId }
    val isMounted    = container?.isMounted ?: false

    val hazeState = remember { HazeState() }

    var showMoreMenu         by remember { mutableStateOf(false) }
    var showRenameDialog     by remember { mutableStateOf(false) }
    var showMoveSheet        by remember { mutableStateOf(false) }
    var showAutoUnmountSheet by remember { mutableStateOf(false) }
    var showRemoveBioDialog  by remember { mutableStateOf(false) }
    var renameText           by remember { mutableStateOf("") }

    LaunchedEffect(renameResult) {
        if (renameResult is VaultViewModel.RenameResult.Success) {
            showRenameDialog = false
            viewModel.clearRenameResult()
        }
    }

    LaunchedEffect(containerId, container?.hasBiometric) {
        if (container?.hasBiometric == true) {
            viewModel.hasBiometricCredentials(containerId)
        }
    }

    fun runAfterUnmountIfNeeded(action: () -> Unit) {
        if (isMounted) {
            viewModel.unmountContainer(containerId) { action() }
        } else {
            action()
        }
    }

    val topBarColors  = if (isAmoled) TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                        else TopAppBarDefaults.topAppBarColors()
    val topBarHazeMod = if (isAmoled) Modifier.hazeEffect(state = hazeState, style = ArcanumHazeStyle.topBar)
                        else Modifier

    CompositionLocalProvider(LocalHazeState provides hazeState) {
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
                                    onClick     = {
                                        showMoreMenu = false
                                        runAfterUnmountIfNeeded {
                                            renameText   = container?.name ?: ""
                                            showRenameDialog = true
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text        = { Text(stringResource(R.string.vault_config_move_sheet_title)) },
                                    leadingIcon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                                    onClick     = {
                                        showMoreMenu = false
                                        runAfterUnmountIfNeeded {
                                            showMoveSheet = true
                                        }
                                    }
                                )
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
                    VaultConfigHero(container = container, isDynamic = isDynamic)

                    // ── Operations ───────────────────────────────────────────────
                    VaultOperationItem(
                        icon      = if (isMounted) Icons.Outlined.LockOpen else Icons.Outlined.Lock,
                        rawColor  = Color(0xFF16A34A),
                        title     = stringResource(if (isMounted) R.string.vault_config_op_open else R.string.vault_config_op_mount),
                        subtitle  = stringResource(if (isMounted) R.string.vault_config_op_open_desc else R.string.vault_config_op_mount_desc),
                        isDynamic = isDynamic,
                            onClick   = { if (isMounted) onOpenVault(containerId) else onMount(containerId, false) }
                    )
                    if (isMounted) {
                        VaultOperationItem(
                            icon      = Icons.Outlined.Lock,
                            rawColor  = Color(0xFF2563EB),
                            title     = stringResource(R.string.vault_config_op_unmount),
                            subtitle  = stringResource(R.string.vault_config_op_unmount_desc),
                            isDynamic = isDynamic,
                            onClick   = {
                                viewModel.unmountContainer(containerId) { onUnmountVault(containerId) }
                            }
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

                    if (container != null) {
                        SettingsSwitch(
                            title           = stringResource(R.string.vault_config_biometric_unlock),
                            subtitle        = stringResource(
                                when {
                                    !biometricUnlockEnabled && container.hasBiometric ->
                                        R.string.vault_config_biometric_saved_global_disabled
                                    !biometricUnlockEnabled && !container.hasBiometric ->
                                        R.string.vault_config_biometric_global_disabled
                                    container.hasBiometric ->
                                        R.string.vault_config_biometric_enabled_desc
                                    else ->
                                        R.string.vault_config_biometric_enable_desc
                                }
                            ),
                            checked         = container.hasBiometric,
                            enabled         = biometricUnlockEnabled || container.hasBiometric,
                            onCheckedChange = { enabled ->
                                if (!enabled && container.hasBiometric) {
                                    showRemoveBioDialog = true
                                } else if (enabled && biometricUnlockEnabled && !container.hasBiometric) {
                                    runAfterUnmountIfNeeded { onMount(containerId, true) }
                                }
                            }
                        )
                    }

                    VaultOperationItem(
                        icon      = Icons.Outlined.Lock,
                        rawColor  = Color(0xFF1E88E5),
                        title     = stringResource(R.string.vault_config_change_password),
                        subtitle  = stringResource(if (isMounted) R.string.vault_config_auto_unmount_before_action else R.string.chpwd_config_desc),
                        isDynamic = isDynamic,
                        onClick   = { runAfterUnmountIfNeeded { onChangePassword(containerId) } }
                    )
                    VaultOperationItem(
                        icon      = Icons.Outlined.VpnKey,
                        rawColor  = Color(0xFF7B1FA2),
                        title     = stringResource(R.string.vault_config_change_keyfile),
                        subtitle  = stringResource(if (isMounted) R.string.vault_config_auto_unmount_before_action else R.string.chkeyfile_config_desc),
                        isDynamic = isDynamic,
                        onClick   = { runAfterUnmountIfNeeded { onChangeKeyfile(containerId) } }
                    )

                    VaultOperationItem(
                        icon      = Icons.Outlined.SaveAlt,
                        rawColor  = Color(0xFFE65100),
                        title     = stringResource(R.string.vault_menu_backup),
                        subtitle  = stringResource(if (isMounted) R.string.vault_config_backup_auto_unmount_desc else R.string.vault_config_backup_desc),
                        isDynamic = isDynamic,
                        onClick   = { runAfterUnmountIfNeeded { onBackup(containerId) } }
                    )
                    VaultOperationItem(
                        icon      = Icons.Outlined.OpenInFull,
                        rawColor  = Color(0xFF8E24AA),
                        title     = stringResource(R.string.vault_info_op_expand_volume),
                        subtitle  = stringResource(if (isMounted) R.string.vault_config_auto_unmount_before_action else R.string.vault_card_expand_desc),
                        isDynamic = isDynamic,
                        onClick   = { runAfterUnmountIfNeeded { onExpandVolume(containerId) } }
                    )

                    Spacer(Modifier.navigationBarsPadding())
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        if (showRemoveBioDialog && container != null) {
            AppDialog(
                onDismissRequest = { showRemoveBioDialog = false },
                title            = { Text(stringResource(R.string.vault_remove_biometric_title)) },
                text             = { Text(stringResource(R.string.vault_remove_biometric_body, container.name)) },
                confirmButton    = {
                    TextButton(onClick = {
                        showRemoveBioDialog = false
                        viewModel.deleteBiometricCredentials(containerId)
                    }) { Text(stringResource(R.string.vault_remove_confirm)) }
                },
                dismissButton    = {
                    TextButton(onClick = { showRemoveBioDialog = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            )
        }

        // ── Rename dialog ─────────────────────────────────────────────────────────
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
                            trailingContent   = { Icon(Icons.Outlined.KeyboardArrowRight, contentDescription = null) },
                            modifier          = Modifier.clickable {
                                showMoveSheet = false
                                onMoveVault(containerId, true)
                            }
                        )
                    }
                    androidx.compose.material3.ListItem(
                        headlineContent   = { Text(stringResource(R.string.vault_config_move_to_internal)) },
                        supportingContent = { Text(stringResource(R.string.vault_config_move_to_internal_desc), style = MaterialTheme.typography.bodySmall) },
                        trailingContent   = { Icon(Icons.Outlined.KeyboardArrowRight, contentDescription = null) },
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
    }
}

// ── Hero ──────────────────────────────────────────────────────────────────────

@Composable
private fun VaultConfigHero(
    container: ContainerEntity?,
    isDynamic: Boolean
) {
    val heroIcon = Icons.Outlined.Storage

    val displayPath = remember(container?.path, container?.safUri) {
        when {
            container == null -> ""
            container.path.isNotBlank() -> container.path
            container.safUri.isNotBlank() -> {
                val seg = android.net.Uri.decode(
                    android.net.Uri.parse(container.safUri).lastPathSegment ?: ""
                )
                val after = seg.substringAfter(':')
                if (after.isNotEmpty()) "/$after" else seg
            }
            else -> ""
        }
    }

    Column(
        modifier            = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier         = Modifier
                .size(96.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector        = heroIcon,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.primary,
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
