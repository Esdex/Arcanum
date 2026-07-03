package zip.arcanum.arcanum.containers.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.SortByAlpha
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import androidx.compose.material3.TopAppBarDefaults
import zip.arcanum.core.theme.ArcanumHazeStyle
import zip.arcanum.core.theme.LocalAmoledMode
import zip.arcanum.R
import zip.arcanum.core.components.AppDialog
import zip.arcanum.core.components.AppSheet
import zip.arcanum.core.components.EmptyStateView
import zip.arcanum.core.components.LocalHazeState
import zip.arcanum.core.components.UpgradeOverlay
import zip.arcanum.core.database.entities.ContainerEntity
import zip.arcanum.core.notifications.InAppNotification
import zip.arcanum.core.notifications.InAppNotificationBanner
import java.text.DecimalFormat
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Button
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle


@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VaultScreen(
    onLock: () -> Unit,
    onCreateContainer: () -> Unit,
    onOpenSettings: () -> Unit,
    onVaultConfig: (containerId: String) -> Unit,
    onMountContainer: (containerId: String) -> Unit = {},
    onMountSuccess: (id: String) -> Unit = {},
    onUnmountStart: (containerId: String) -> Unit = {},
    unmountedContainerId: String? = null,
    onUnmountedIconPositioned: (Offset) -> Unit = {},
    suppressBackHandler: Boolean = false,
    autoMountContainerId: String? = null,
    onAutoMountHandled: () -> Unit = {},
    onOpenWhatsNew: () -> Unit = {},
    viewModel: VaultViewModel = hiltViewModel()
) {
    val context              = LocalContext.current
    val containers           by viewModel.containers.collectAsState()
    val canAddMoreContainers by viewModel.canAddMoreContainers.collectAsState()
    val addVaultResult       by viewModel.addVaultResult.collectAsState()
    val sortState            by viewModel.sortState.collectAsState()
    val showUpdateBanner     by viewModel.showUpdateBanner.collectAsState()
    val hazeState      = remember { HazeState() }
    val isAmoled       = LocalAmoledMode.current
    val topBarColors   = if (isAmoled) TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                         else TopAppBarDefaults.topAppBarColors()
    val topBarHazeMod  = if (isAmoled) Modifier.hazeEffect(state = hazeState, style = ArcanumHazeStyle.topBar)
                         else Modifier

    var showSortSheet      by remember { mutableStateOf(false) }
    var fabExpanded        by remember { mutableStateOf(false) }
    var showLockDialog     by remember { mutableStateOf(false) }
    var containerToUnmount        by remember { mutableStateOf<ContainerEntity?>(null) }
    var containerToRemoveFromList by remember { mutableStateOf<ContainerEntity?>(null) }
    var notification              by remember { mutableStateOf<InAppNotification?>(null) }
    var selectionMode      by remember { mutableStateOf(false) }
    var selectedIds        by remember { mutableStateOf(emptySet<String>()) }
    var contextMenuContainerId   by remember { mutableStateOf<String?>(null) }
    var showUpgradeDialog            by remember { mutableStateOf(false) }
    var containerNotFound            by remember { mutableStateOf<ContainerEntity?>(null) }
    var showRemoveNotFoundConfirm    by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.initVersionCheck() }

    LaunchedEffect(showUpdateBanner) {
        if (showUpdateBanner) notification = InAppNotification.AppUpdated
    }

    // Unmount containers per their per-vault config on app stop
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.unmountContainersOnStop(isLocked = keyguard.isKeyguardLocked)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(autoMountContainerId, containers) {
        val id = autoMountContainerId ?: return@LaunchedEffect
        if (containers.none { it.id == id }) return@LaunchedEffect
        onMountContainer(id)
        onAutoMountHandled()
    }

    // Convert add-vault result to notification (or upgrade dialog for limit)
    LaunchedEffect(addVaultResult) {
        val result = addVaultResult ?: return@LaunchedEffect
        when (result) {
            is VaultViewModel.AddVaultResult.Added         -> notification = InAppNotification.VaultAdded(result.fileName)
            is VaultViewModel.AddVaultResult.AlreadyExists -> notification = InAppNotification.VaultAlreadyExists(result.fileName)
            VaultViewModel.AddVaultResult.InvalidFile      -> notification = InAppNotification.VaultInvalidFile
            VaultViewModel.AddVaultResult.LimitReached     -> showUpgradeDialog = true
            is VaultViewModel.AddVaultResult.Error         -> notification = InAppNotification.VaultAddError(result.message)
        }
        viewModel.clearAddVaultResult()
    }

    // FAB rotation animation
    val fabRotation by animateFloatAsState(
        targetValue   = if (fabExpanded) 45f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label         = "fab_rotation"
    )
    val scrimAlpha  by animateFloatAsState(
        targetValue   = if (fabExpanded || contextMenuContainerId != null) 0.6f else 0f,
        animationSpec = tween(300),
        label         = "scrim_alpha"
    )

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.addContainerFromUri(uri)
    }

    LaunchedEffect(selectionMode) {
        if (selectionMode) { fabExpanded = false; contextMenuContainerId = null }
    }

    BackHandler(enabled = !suppressBackHandler) {
        when {
            selectionMode -> { selectionMode = false; selectedIds = emptySet() }
            fabExpanded   -> fabExpanded = false
            else          -> showLockDialog = true
        }
    }

    val appStorageLabel   = stringResource(R.string.vault_storage_app)
    val localStorageLabel = stringResource(R.string.vault_storage_local)
    val navBarPadding     = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    CompositionLocalProvider(LocalHazeState provides hazeState) {
        Box(Modifier.fillMaxSize()) {

            // ── Main content ──────────────────────────────────────────────────
            Scaffold(
                topBar = {
                    if (selectionMode) {
                        TopAppBar(
                            modifier = topBarHazeMod,
                            colors   = topBarColors,
                            navigationIcon = {
                                IconButton(onClick = {
                                    selectionMode = false
                                    selectedIds = emptySet()
                                }) {
                                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.vault_cd_exit_selection))
                                }
                            },
                            title = { Text(stringResource(R.string.vault_selection_count, selectedIds.size)) },
                            actions = {
                                TextButton(onClick = {
                                    selectedIds = containers.map { it.id }.toSet()
                                }) {
                                    Text(stringResource(R.string.vault_select_all))
                                }
                                IconButton(
                                    onClick = {
                                        viewModel.deleteContainers(selectedIds)
                                        selectionMode = false
                                        selectedIds = emptySet()
                                    },
                                    enabled = selectedIds.isNotEmpty()
                                ) {
                                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.vault_cd_delete_selected))
                                }
                            }
                        )
                    } else {
                        TopAppBar(
                            modifier = topBarHazeMod,
                            colors   = topBarColors,
                            title   = { Text(stringResource(R.string.vault_title), fontWeight = FontWeight.SemiBold) },
                            actions = {
                                IconButton(onClick = { showSortSheet = true }) {
                                    Icon(Icons.Outlined.FilterList, contentDescription = stringResource(R.string.vault_cd_sort_group))
                                }
                                IconButton(onClick = { fabExpanded = false; onOpenSettings() }) {
                                    Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.vault_cd_settings))
                                }
                            }
                        )
                    }
                }
            ) { innerPadding ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .hazeSource(hazeState)
                ) {
                    if (containers.isEmpty()) {
                        EmptyStateView(
                            title     = stringResource(R.string.vault_empty_title),
                            subtitle  = stringResource(R.string.vault_empty_subtitle),
                            lottieRes = R.raw.ghost,
                            modifier  = Modifier
                                .fillMaxSize()
                                .padding(top = innerPadding.calculateTopPadding())
                                .padding(bottom = 80.dp + navBarPadding)
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(
                                top    = innerPadding.calculateTopPadding(),
                                bottom = 80.dp + navBarPadding
                            ),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            if (sortState.groupBy == VaultViewModel.GroupBy.LOCATION) {
                                val grouped = containers.groupBy { c ->
                                    if (c.safUri.isEmpty() &&
                                        (c.path.startsWith(context.filesDir.absolutePath) ||
                                         c.path.startsWith(context.noBackupFilesDir.absolutePath)))
                                        appStorageLabel else localStorageLabel
                                }.entries.sortedBy { it.key }
                                grouped.forEach { (groupName, groupList) ->
                                    stickyHeader(key = "hdr_$groupName") {
                                        Text(
                                            text     = groupName,
                                            style    = MaterialTheme.typography.labelMedium,
                                            color    = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(MaterialTheme.colorScheme.surface)
                                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                        )
                                    }
                                    items(groupList, key = { it.id }) { container ->
                                        VaultCardItem(
                                            container              = container,
                                            selectedIds            = selectedIds,
                                            selectionMode          = selectionMode,
                                            unmountedContainerId   = unmountedContainerId,
                                            onUnmountedIconPositioned = onUnmountedIconPositioned,
                                            contextMenuContainerId = contextMenuContainerId,
                                            onContextMenuChange    = { open -> contextMenuContainerId = if (open) container.id else null },
                                            onSelect               = { selectedIds = if (container.id in selectedIds) selectedIds - container.id else selectedIds + container.id },
                                            onOpen                 = {
                                                if (container.isMounted || isContainerAccessible(context, container)) {
                                                    onVaultConfig(container.id)
                                                } else {
                                                    containerNotFound = container
                                                }
                                            },
                                            onLongClick            = { selectionMode = true; selectedIds = selectedIds + container.id },
                                            onUnmount              = { containerToUnmount = container },
                                            onRemoveFromList       = { containerToRemoveFromList = container }
                                        )
                                    }
                                }
                            } else {
                                items(containers, key = { it.id }) { container ->
                                    VaultCardItem(
                                        container              = container,
                                        selectedIds            = selectedIds,
                                        selectionMode          = selectionMode,
                                        unmountedContainerId   = unmountedContainerId,
                                        onUnmountedIconPositioned = onUnmountedIconPositioned,
                                        contextMenuContainerId = contextMenuContainerId,
                                        onContextMenuChange    = { open -> contextMenuContainerId = if (open) container.id else null },
                                        onSelect               = { selectedIds = if (container.id in selectedIds) selectedIds - container.id else selectedIds + container.id },
                                        onOpen                 = {
                                            if (container.isMounted || isContainerAccessible(context, container)) {
                                                onVaultConfig(container.id)
                                            } else {
                                                containerNotFound = container
                                            }
                                        },
                                        onLongClick            = { selectionMode = true; selectedIds = selectedIds + container.id },
                                        onUnmount              = { containerToUnmount = container },
                                        onRemoveFromList       = { containerToRemoveFromList = container }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Scrim ─────────────────────────────────────────────────────────
            if (!selectionMode && (fabExpanded || contextMenuContainerId != null || scrimAlpha > 0f)) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .zIndex(3f)
                        .alpha(scrimAlpha)
                        .background(Color.Black)
                        .clickable(enabled = fabExpanded || contextMenuContainerId != null) {
                            fabExpanded = false
                            contextMenuContainerId = null
                        }
                )
            }

            // ── FAB menu items ────────────────────────────────────────────────
            if (!selectionMode) Column(
                modifier             = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 8.dp, bottom = 88.dp)
                    .zIndex(4f),
                verticalArrangement  = Arrangement.spacedBy(8.dp),
                horizontalAlignment  = Alignment.End
            ) {
                // "Open existing" — above, appears second (50 ms delay)
                AnimatedVisibility(
                    visible = fabExpanded,
                    enter   = slideInVertically(
                        animationSpec  = tween(300, delayMillis = 50),
                        initialOffsetY = { it / 2 }
                    ) + fadeIn(tween(200, delayMillis = 50)),
                    exit    = slideOutVertically(tween(180), targetOffsetY = { it / 2 }) + fadeOut(tween(150))
                ) {
                    FabMenuItem(
                        icon    = Icons.Outlined.FolderOpen,
                        label   = stringResource(R.string.vault_fab_open_existing),
                        onClick = {
                            fabExpanded = false
                            if (canAddMoreContainers) openDocumentLauncher.launch(arrayOf("*/*"))
                            else showUpgradeDialog = true
                        }
                    )
                }
                // "Create new" — below, appears first (0 ms delay)
                AnimatedVisibility(
                    visible = fabExpanded,
                    enter   = slideInVertically(
                        animationSpec  = tween(300),
                        initialOffsetY = { it / 2 }
                    ) + fadeIn(tween(200)),
                    exit    = slideOutVertically(tween(200), targetOffsetY = { it / 2 }) + fadeOut(tween(150))
                ) {
                    FabMenuItem(
                        icon    = Icons.Outlined.CreateNewFolder,
                        label   = stringResource(R.string.vault_fab_create_new),
                        onClick = {
                            fabExpanded = false
                            if (canAddMoreContainers) onCreateContainer()
                            else showUpgradeDialog = true
                        }
                    )
                }
            }

            // ── Diamond FAB ───────────────────────────────────────────────────
            if (!selectionMode) Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(16.dp)
                    .zIndex(5f)
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .rotate(fabRotation)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { fabExpanded = !fabExpanded },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector        = Icons.Rounded.Add,
                        contentDescription = if (fabExpanded) stringResource(R.string.vault_cd_close_fab) else stringResource(R.string.vault_cd_new_vault_fab),
                        tint               = MaterialTheme.colorScheme.onPrimary,
                        modifier           = Modifier.size(24.dp)
                    )
                }
            }

            // ── Notification banner ───────────────────────────────────────────
            InAppNotificationBanner(
                notification = notification,
                onDismiss    = { notification = null },
                onAction     = { notif ->
                    if (notif is InAppNotification.AppUpdated) {
                        viewModel.markUpdateSeen()
                        onOpenWhatsNew()
                    }
                    notification = null
                },
                modifier     = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .zIndex(10f)
            )

            // ── Unmount confirm dialog ────────────────────────────────────────
            containerToUnmount?.let { c ->
                AppDialog(
                    onDismissRequest = { containerToUnmount = null },
                    title            = { Text(stringResource(R.string.vault_unmount_title, c.name)) },
                    text             = { Text(stringResource(R.string.vault_unmount_body)) },
                    confirmButton    = {
                        TextButton(onClick = {
                            containerToUnmount = null
                            viewModel.unmountContainer(c.id) { onUnmountStart(c.id) }
                        }) { Text(stringResource(R.string.vault_unmount_confirm)) }
                    },
                    dismissButton    = {
                        TextButton(onClick = { containerToUnmount = null }) { Text(stringResource(R.string.common_cancel)) }
                    }
                )
            }

            // ── Remove-from-list confirm dialog ──────────────────────────────
            containerToRemoveFromList?.let { c ->
                AppDialog(
                    onDismissRequest = { containerToRemoveFromList = null },
                    title            = { Text(stringResource(R.string.vault_remove_title, c.name)) },
                    text             = { Text(stringResource(R.string.vault_remove_body)) },
                    confirmButton    = {
                        TextButton(onClick = {
                            containerToRemoveFromList = null
                            viewModel.removeFromList(c.id)
                        }) { Text(stringResource(R.string.vault_remove_confirm)) }
                    },
                    dismissButton    = {
                        TextButton(onClick = { containerToRemoveFromList = null }) { Text(stringResource(R.string.common_cancel)) }
                    }
                )
            }

            // ── Upgrade overlay ───────────────────────────────────────────────
            if (showUpgradeDialog) {
                UpgradeOverlay(onDismiss = { showUpgradeDialog = false })
            }

            // ── Container not found overlay ───────────────────────────────────
            // Keep last non-null container so content stays alive during exit animation
            val overlayContainer = remember { mutableStateOf<ContainerEntity?>(null) }
            if (containerNotFound != null) overlayContainer.value = containerNotFound
            AnimatedVisibility(
                visible  = containerNotFound != null,
                enter    = fadeIn(tween(250)),
                exit     = fadeOut(tween(200)),
                modifier = Modifier.zIndex(30f)
            ) {
                val c = overlayContainer.value ?: return@AnimatedVisibility
                ContainerNotFoundOverlay(
                    container        = c,
                    onBack           = { containerNotFound = null },
                    onRemoveFromList = { showRemoveNotFoundConfirm = true }
                )
                if (showRemoveNotFoundConfirm) {
                    AppDialog(
                        onDismissRequest = { showRemoveNotFoundConfirm = false },
                        title            = { Text(stringResource(R.string.vault_not_found_confirm_title)) },
                        text             = { Text(stringResource(R.string.vault_not_found_confirm_body)) },
                        confirmButton    = {
                            TextButton(onClick = {
                                showRemoveNotFoundConfirm = false
                                containerNotFound         = null
                                viewModel.removeFromList(c.id)
                            }) { Text(stringResource(R.string.vault_not_found_remove)) }
                        },
                        dismissButton    = {
                            TextButton(onClick = { showRemoveNotFoundConfirm = false }) {
                                Text(stringResource(R.string.common_cancel))
                            }
                        }
                    )
                }
            }

            // ── Lock dialog ───────────────────────────────────────────────────
            if (showLockDialog) {
                AppDialog(
                    onDismissRequest = { showLockDialog = false },
                    title            = { Text(stringResource(R.string.vault_lock_title)) },
                    text             = { Text(stringResource(R.string.vault_lock_body)) },
                    confirmButton    = {
                        TextButton(onClick = { showLockDialog = false; onLock() }) { Text(stringResource(R.string.vault_lock_confirm)) }
                    },
                    dismissButton    = {
                        TextButton(onClick = { showLockDialog = false }) { Text(stringResource(R.string.common_cancel)) }
                    }
                )
            }

            // ── Sort / group sheet ────────────────────────────────────────────
            if (showSortSheet) {
                SortFilterSheet(
                    sortState        = sortState,
                    onSortItemClick  = { sortBy ->
                        val newDir = if (sortState.sortBy == sortBy) {
                            if (sortState.direction == VaultViewModel.SortDirection.ASCENDING)
                                VaultViewModel.SortDirection.DESCENDING
                            else VaultViewModel.SortDirection.ASCENDING
                        } else {
                            VaultViewModel.SortDirection.ASCENDING
                        }
                        viewModel.updateSort(sortBy, newDir)
                    },
                    onGroupByClick      = { viewModel.updateGroupBy(it) },
                    onBiometricFirstToggle = { viewModel.toggleBiometricFirst() },
                    onDismiss           = { showSortSheet = false }
                )
            }

        } // Box
    } // CompositionLocalProvider
}

// ── VaultCardItem (thin wrapper used by both flat and grouped list) ───────────

@Composable
private fun VaultCardItem(
    container: ContainerEntity,
    selectedIds: Set<String>,
    selectionMode: Boolean,
    unmountedContainerId: String?,
    onUnmountedIconPositioned: (Offset) -> Unit,
    contextMenuContainerId: String?,
    onContextMenuChange: (Boolean) -> Unit,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
    onLongClick: () -> Unit,
    onUnmount: () -> Unit,
    onRemoveFromList: () -> Unit,
) {
    VaultCard(
        container               = container,
        isSelected              = container.id in selectedIds,
        inSelectionMode         = selectionMode,
        isBeingUnmounted        = container.id == unmountedContainerId,
        onIconPositioned        = onUnmountedIconPositioned,
        onLockIconClick         = if (container.isMounted && !selectionMode) onUnmount else null,
        showContextMenu         = container.id == contextMenuContainerId,
        onShowContextMenuChange = onContextMenuChange,
        onClick                 = { if (selectionMode) onSelect() else onOpen() },
        onLongClick             = onLongClick,
        onRemoveFromList        = onRemoveFromList
    )
}

// ── Sort & group sheet ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortFilterSheet(
    sortState: VaultViewModel.SortState,
    onSortItemClick: (VaultViewModel.SortBy) -> Unit,
    onGroupByClick: (VaultViewModel.GroupBy) -> Unit,
    onBiometricFirstToggle: () -> Unit,
    onDismiss: () -> Unit
) {
    AppSheet(
        onDismissRequest = onDismiss,
        sheetState       = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(Modifier.padding(bottom = 32.dp)) {
            Text(
                text       = stringResource(R.string.vault_sort_title),
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier   = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            SortSheetItem(
                icon       = Icons.Outlined.SortByAlpha,
                label      = stringResource(R.string.vault_sort_name),
                isSelected = sortState.sortBy == VaultViewModel.SortBy.NAME,
                direction  = sortState.direction,
                onClick    = { onSortItemClick(VaultViewModel.SortBy.NAME) }
            )
            SortSheetItem(
                icon       = Icons.Outlined.DataUsage,
                label      = stringResource(R.string.vault_sort_size),
                isSelected = sortState.sortBy == VaultViewModel.SortBy.SIZE,
                direction  = sortState.direction,
                onClick    = { onSortItemClick(VaultViewModel.SortBy.SIZE) }
            )
            SortSheetItem(
                icon       = Icons.Outlined.AccessTime,
                label      = stringResource(R.string.vault_sort_last_opened),
                isSelected = sortState.sortBy == VaultViewModel.SortBy.LAST_OPENED,
                direction  = sortState.direction,
                onClick    = { onSortItemClick(VaultViewModel.SortBy.LAST_OPENED) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text     = stringResource(R.string.vault_sort_group_by),
                style    = MaterialTheme.typography.labelMedium,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
            )
            GroupByOption(
                label    = stringResource(R.string.vault_sort_group_none),
                selected = sortState.groupBy == VaultViewModel.GroupBy.NONE,
                onClick  = { onGroupByClick(VaultViewModel.GroupBy.NONE) }
            )
            GroupByOption(
                label    = stringResource(R.string.vault_sort_group_location),
                selected = sortState.groupBy == VaultViewModel.GroupBy.LOCATION,
                onClick  = { onGroupByClick(VaultViewModel.GroupBy.LOCATION) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onBiometricFirstToggle)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector        = Icons.Outlined.Fingerprint,
                    contentDescription = null,
                    tint               = if (sortState.biometricFirst) MaterialTheme.colorScheme.primary
                                         else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier           = Modifier.size(22.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text  = stringResource(R.string.vault_sort_biometric_first),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (sortState.biometricFirst) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text  = stringResource(R.string.vault_sort_biometric_first_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked         = sortState.biometricFirst,
                    onCheckedChange = { onBiometricFirstToggle() }
                )
            }
        }
    }
}

@Composable
private fun SortSheetItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    direction: VaultViewModel.SortDirection,
    onClick: () -> Unit
) {
    val primary   = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val subtle    = MaterialTheme.colorScheme.onSurfaceVariant

    ListItem(
        colors          = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent  = {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = if (isSelected) primary else subtle,
                modifier           = Modifier.size(22.dp)
            )
        },
        headlineContent = {
            Text(
                text  = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isSelected) primary else onSurface
            )
        },
        trailingContent = if (isSelected) ({
            Icon(
                imageVector        = if (direction == VaultViewModel.SortDirection.ASCENDING)
                                         Icons.Outlined.ArrowUpward
                                     else Icons.Outlined.ArrowDownward,
                contentDescription = if (direction == VaultViewModel.SortDirection.ASCENDING) stringResource(R.string.vault_sort_cd_ascending) else stringResource(R.string.vault_sort_cd_descending),
                tint               = primary,
                modifier           = Modifier.size(20.dp)
            )
        }) else null,
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun GroupByOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            text     = label,
            style    = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}


// ── VaultCard ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VaultCard(
    container: ContainerEntity,
    isSelected: Boolean,
    inSelectionMode: Boolean,
    isBeingUnmounted: Boolean = false,
    onIconPositioned: (Offset) -> Unit = {},
    onLockIconClick: (() -> Unit)? = null,
    showContextMenu: Boolean = false,
    onShowContextMenuChange: (Boolean) -> Unit = {},
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRemoveFromList: () -> Unit,
) {
    val context = LocalContext.current
    val appStr   = stringResource(R.string.vault_storage_app)
    val localStr = stringResource(R.string.vault_storage_local)
    val storageLabel = remember(container.path, container.safUri, appStr, localStr) {
        val p = container.path
        when {
            p.startsWith(context.filesDir.absolutePath)         -> appStr
            p.startsWith(context.noBackupFilesDir.absolutePath) -> appStr
            container.safUri.isNotEmpty()                       -> localStr
            else                                                 -> localStr
        }
    }
    val bgColor by animateColorAsState(
        targetValue   = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        else Color.Transparent,
        animationSpec = tween<androidx.compose.ui.graphics.Color>(150),
        label         = "card_sel_bg"
    )
    val density     = LocalDensity.current
    var touchOffset by remember { mutableStateOf(DpOffset.Zero) }
    var menuWidthPx     by remember { mutableIntStateOf(0) }
    var cardHeightPx    by remember { mutableIntStateOf(0) }
    Box {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .onSizeChanged { cardHeightPx = it.height }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    touchOffset = DpOffset(
                        x = with(density) { down.position.x.toDp() },
                        y = with(density) { down.position.y.toDp() }
                    )
                }
            }
            .combinedClickable(
                onClick     = onClick,
                onLongClick = {
                    if (inSelectionMode) onLongClick()
                    else onShowContextMenuChange(true)
                }
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (inSelectionMode) {
            Checkbox(
                checked          = isSelected,
                onCheckedChange  = null,
                modifier         = Modifier.size(24.dp)
            )
        } else {
            val iconBg by animateColorAsState(
                targetValue   = if (container.isMounted) Color(0xFF16A34A)
                                else MaterialTheme.colorScheme.primaryContainer,
                animationSpec = tween<Color>(300),
                label         = "icon_bg"
            )
            val iconTint by animateColorAsState(
                targetValue   = if (container.isMounted) Color.White
                                else MaterialTheme.colorScheme.onPrimaryContainer,
                animationSpec = tween<Color>(300),
                label         = "icon_tint"
            )
            val iconAlpha by animateFloatAsState(
                targetValue   = if (isBeingUnmounted) 0f else 1f,
                animationSpec = tween(300),
                label         = "vault_icon_alpha"
            )
            Surface(
                shape    = RoundedCornerShape(14.dp),
                color    = iconBg,
                modifier = Modifier
                    .size(52.dp)
                    .onGloballyPositioned { coords ->
                        if (isBeingUnmounted) {
                            val pos  = coords.positionInWindow()
                            val size = coords.size
                            onIconPositioned(
                                Offset(pos.x + size.width / 2f, pos.y + size.height / 2f)
                            )
                        }
                    }
                    .then(
                        if (onLockIconClick != null) Modifier.clickable(onClick = onLockIconClick)
                        else Modifier
                    )
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector        = if (container.isMounted) Icons.Outlined.LockOpen
                                            else Icons.Outlined.Lock,
                        contentDescription = null,
                        tint               = iconTint,
                        modifier           = Modifier.size(26.dp).alpha(iconAlpha)
                    )
                    if (container.hasBiometric && !container.isMounted) {
                        Icon(
                            imageVector        = Icons.Outlined.Fingerprint,
                            contentDescription = null,
                            tint               = iconTint.copy(alpha = 0.75f),
                            modifier           = Modifier
                                .size(18.dp)
                                .align(Alignment.BottomEnd)
                                .offset(x = 2.dp, y = 2.dp)
                        )
                    }
                }
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = container.name,
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text  = storageLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (!inSelectionMode) {
            Text(
                text  = container.size.fmtSize(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    DropdownMenu(
        expanded         = showContextMenu,
        onDismissRequest = { onShowContextMenuChange(false) },
        offset           = with(density) {
            DpOffset(
                x = touchOffset.x - menuWidthPx.toDp(),
                y = touchOffset.y - cardHeightPx.toDp()
            )
        },
        modifier         = Modifier.onSizeChanged { menuWidthPx = it.width }
    ) {
        DropdownMenuItem(
            text        = { Text(stringResource(R.string.vault_menu_select)) },
            leadingIcon = { Icon(Icons.Outlined.CheckBox, contentDescription = null) },
            onClick     = { onShowContextMenuChange(false); onLongClick() }
        )
        DropdownMenuItem(
            text        = { Text(stringResource(R.string.vault_menu_remove)) },
            leadingIcon = { Icon(Icons.Outlined.RemoveCircleOutline, contentDescription = null) },
            onClick     = { onShowContextMenuChange(false); onRemoveFromList() }
        )
    }
    } // Box
}

// ── FAB menu item ─────────────────────────────────────────────────────────────

@Composable
private fun FabMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier          = Modifier.padding(bottom = 4.dp)
    ) {
        Card(
            onClick   = onClick,
            shape     = RoundedCornerShape(8.dp),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            Text(
                text     = label,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style    = MaterialTheme.typography.labelMedium
            )
        }
        Spacer(Modifier.width(8.dp))
        Surface(
            shape           = CircleShape,
            tonalElevation  = 4.dp,
            shadowElevation = 4.dp,
            color           = MaterialTheme.colorScheme.secondaryContainer,
            modifier        = Modifier
                .size(40.dp)
                .clickable(onClick = onClick)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun Long.fmtSize(): String {
    val gb  = this / (1024.0 * 1024.0 * 1024.0)
    val mb  = this / (1024.0 * 1024.0)
    val fmt = DecimalFormat("#.#")
    return when {
        gb >= 1.0 -> "${fmt.format(gb)} GB"
        mb >= 1.0 -> "${fmt.format(mb)} MB"
        else      -> "${fmt.format(this / 1024.0)} KB"
    }
}

private fun Long.fmtDate(): String = when (this) {
    0L   -> "Never opened"
    else -> {
        val date  = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()
        val today = java.time.LocalDate.now(ZoneId.systemDefault())
        when (date) {
            today              -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        }
    }
}

private fun isContainerAccessible(context: android.content.Context, container: ContainerEntity): Boolean {
    return if (container.safUri.isNotEmpty()) {
        try {
            context.contentResolver.openFileDescriptor(android.net.Uri.parse(container.safUri), "r")?.use { true } ?: false
        } catch (_: Exception) { false }
    } else {
        java.io.File(container.path).exists()
    }
}

@Composable
private fun ContainerNotFoundOverlay(
    container: ContainerEntity,
    onBack: () -> Unit,
    onRemoveFromList: () -> Unit
) {
    androidx.activity.compose.BackHandler { onBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(20f)
            .background(Color.Black)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication        = null
            ) {}
    ) {
        Column(
            modifier            = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier         = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.FolderOff,
                    contentDescription = null,
                    tint     = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(48.dp)
                )
            }
            Spacer(Modifier.height(32.dp))
            Text(
                text       = stringResource(R.string.vault_not_found_title),
                style      = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color      = Color.White,
                textAlign  = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text      = stringResource(R.string.vault_not_found_body),
                style     = MaterialTheme.typography.bodyMedium,
                color     = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
        }

        Column(
            modifier            = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
                .padding(horizontal = 40.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Button(
                onClick  = onBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.common_back))
            }
            TextButton(
                onClick  = onRemoveFromList,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text  = stringResource(R.string.vault_not_found_remove),
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
        }
    }
}

