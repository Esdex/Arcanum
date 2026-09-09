package zip.arcanum.arcanum.containers.ui

import android.provider.DocumentsContract
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.EnhancedEncryption
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.SortByAlpha
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.FolderOpen
import zip.arcanum.usb.isExtendedContainer
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.CheckBox
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.pluralStringResource
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
import zip.arcanum.core.components.groupShape
import zip.arcanum.core.components.AppSheet
import zip.arcanum.core.components.EmptyStateView
import zip.arcanum.core.components.LocalHazeState
import zip.arcanum.core.components.UpgradeOverlay
import zip.arcanum.core.icons.ArcanumIcons
import zip.arcanum.core.database.entities.ContainerEntity
import zip.arcanum.core.notifications.InAppNotification
import zip.arcanum.core.notifications.LocalNotifications
import java.text.DecimalFormat
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Button
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle


@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VaultScreen(
    onLock: () -> Unit,
    onCreateContainer: () -> Unit,
    onGenerateKeyfile: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onVaultInfo: (containerId: String) -> Unit,
    onOpenVault: (containerId: String) -> Unit = {},
    onMountContainer: (containerId: String) -> Unit = {},
    onMountSuccess: (id: String) -> Unit = {},
    onUnmountStart: (containerId: String) -> Unit = {},
    autoMountContainerId: String? = null,
    onAutoMountHandled: () -> Unit = {},
    onOpenWhatsNew: () -> Unit = {},
    onOpenDonations: () -> Unit = {},
    onOpenPremium: () -> Unit = {},
    viewModel: VaultViewModel = hiltViewModel()
) {
    val context              = LocalContext.current
    val containers           by viewModel.containers.collectAsState()
    val missingIds           by viewModel.missingContainerIds.collectAsState()
    val pendingRelocate      by viewModel.pendingRelocate.collectAsState()
    val canAddMoreContainers by viewModel.canAddMoreContainers.collectAsState()
    val addVaultResult       by viewModel.addVaultResult.collectAsState()
    val usbLayout            by viewModel.usbLayout.collectAsState()
    val sortState            by viewModel.sortState.collectAsState()
    val showUpdateBanner     by viewModel.showUpdateBanner.collectAsState()
    val supportPrompt        by viewModel.supportPrompt.collectAsState()
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
    val notifications             = LocalNotifications.current
    var selectionMode      by remember { mutableStateOf(false) }
    var selectedIds        by remember { mutableStateOf(emptySet<String>()) }
    var showForgetSelectedDialog by remember { mutableStateOf(false) }
    var contextMenuContainerId   by remember { mutableStateOf<String?>(null) }
    var showUpgradeDialog            by remember { mutableStateOf(false) }
    var containerNotFound            by remember { mutableStateOf<ContainerEntity?>(null) }
    var showRemoveNotFoundConfirm    by remember { mutableStateOf(false) }
    var showOpenVaultSheet           by remember { mutableStateOf(false) }
    var showUsbInsertPrompt          by remember { mutableStateOf(false) }
    var showUsbMissing               by remember { mutableStateOf(false) }
    var renameContainer              by remember { mutableStateOf<ContainerEntity?>(null) }
    var renameText                   by remember { mutableStateOf("") }
    var pendingUsbAction             by remember { mutableStateOf<(() -> Unit)?>(null) }
    val usbScope                     = rememberCoroutineScope()
    var showAppStoragePicker         by remember { mutableStateOf(false) }
    val appStorageRoot               = remember(context) { context.filesDir.parentFile!! }
    var appStorageCurrentDir         by remember { mutableStateOf(appStorageRoot) }
    var appStorageEntries            by remember { mutableStateOf<List<java.io.File>>(emptyList()) }

    /*
     * What a tap on a vault does.
     *
     * The thing you came for: an open vault is entered, a closed one asks for its password.
     * It used to open the vault's own screen, which is a page of settings - a detour for the
     * one action out of ten that anybody wants. That screen is now the last item of the
     * long-press menu, where the other rarely-wanted things already were.
     *
     * A vault on a drive is checked before the mount screen opens rather than after a
     * password has been typed into it - the same order the vault's own screen uses.
     */
    val openVault: (ContainerEntity) -> Unit = { container ->
        when {
            container.isMounted -> onOpenVault(container.id)
            container.usbSaltHash.isNotEmpty() -> {
                pendingUsbAction = { onMountContainer(container.id) }
                usbScope.launch {
                    if (viewModel.isUsbDriveAttached() && viewModel.ensureUsbPermission()) {
                        pendingUsbAction = null
                        onMountContainer(container.id)
                    } else {
                        showUsbMissing = true
                    }
                }
            }
            isContainerAccessible(context, container) -> onMountContainer(container.id)
            else -> containerNotFound = container
        }
    }

    val renameResult by viewModel.renameResult.collectAsState()
    LaunchedEffect(renameResult) {
        when (val r = renameResult) {
            is VaultViewModel.RenameResult.Success -> {
                renameContainer = null
                viewModel.clearRenameResult()
            }
            is VaultViewModel.RenameResult.Error -> {
                notifications.notify(InAppNotification.VaultAddError(r.message))
                renameContainer = null
                viewModel.clearRenameResult()
            }
            else -> Unit
        }
    }

    LaunchedEffect(Unit) { viewModel.initVersionCheck() }

    LaunchedEffect(showUpdateBanner) {
        if (showUpdateBanner) notifications.notify(InAppNotification.AppUpdated)
    }

    // The support prompt yields to anything already on screen - an update banner or a vault
    // operation result is what the user was actually doing, and both outrank an ask. That
    // yielding is the queue's job now: an announcement never interrupts and never queues
    // ahead of work (#135).
    LaunchedEffect(Unit) { viewModel.checkSupportPrompt() }
    LaunchedEffect(supportPrompt) {
        val prompt = supportPrompt ?: return@LaunchedEffect
        notifications.notify(prompt)
        viewModel.markSupportPromptShown()
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
            is VaultViewModel.AddVaultResult.Added         -> notifications.notify(InAppNotification.VaultAdded(result.fileName))
            is VaultViewModel.AddVaultResult.AlreadyExists -> notifications.notify(InAppNotification.VaultAlreadyExists(result.fileName))
            VaultViewModel.AddVaultResult.InvalidFile      -> notifications.notify(InAppNotification.VaultInvalidFile)
            VaultViewModel.AddVaultResult.LimitReached     -> showUpgradeDialog = true
            is VaultViewModel.AddVaultResult.Error         -> notifications.notify(InAppNotification.VaultAddError(result.message))
            is VaultViewModel.AddVaultResult.Relocated     -> notifications.notify(
                if (result.sizeChanged) InAppNotification.VaultRelocatedSizeDiffers(result.fileName)
                else                    InAppNotification.VaultRelocated(result.fileName)
            )
            VaultViewModel.AddVaultResult.NoUsbDrive       -> showUsbInsertPrompt = true
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

    // Where the picker should open. A vault file is rarely in Recent, which is where the
    // picker lands by default, so the user starts on an empty screen and has to find the
    // roots drawer - which some OEM profiles do not draw at all (#126). Start at the folder
    // of a vault already added, or at internal storage when there is none.
    val pickerStartUri = remember(containers) {
        containers.firstOrNull { it.safUri.isNotEmpty() }
            ?.let { runCatching { android.net.Uri.parse(it.safUri) }.getOrNull() }
            ?: DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:")
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = OpenDocumentStartingAt(pickerStartUri)
    ) { uri ->
        if (uri != null) viewModel.addContainerFromUri(uri)
    }

    /* The vault whose file is being looked for, kept across the trip to the picker: the
       overlay is gone by the time the result comes back. */
    var relocatingId by remember { mutableStateOf<String?>(null) }
    val relocateLauncher = rememberLauncherForActivityResult(
        contract = OpenDocumentStartingAt(pickerStartUri)
    ) { uri ->
        val id = relocatingId
        relocatingId = null
        if (uri != null && id != null) viewModel.relocateContainer(id, uri)
    }

    LaunchedEffect(selectionMode) {
        if (selectionMode) { fabExpanded = false; contextMenuContainerId = null }
    }

    LaunchedEffect(showAppStoragePicker, appStorageCurrentDir) {
        if (!showAppStoragePicker) return@LaunchedEffect
        val dir = appStorageCurrentDir
        appStorageEntries = withContext(Dispatchers.IO) {
            val isRoot              = dir == appStorageRoot
            val isInsideFiles     = dir.parentFile == appStorageRoot && dir.name == "files"
            val isInsideNoBackup  = dir.parentFile == appStorageRoot && dir.name == "no_backup"
            val filesExclusions   = setOf("datastore", "profileInstalled")
            val noBackupExclusions = setOf(
                "androidx.work.workdb",
                "androidx.work.workdb-shm",
                "androidx.work.workdb-wal"
            )
            (dir.listFiles() ?: emptyArray())
                .filter { entry ->
                    when {
                        isRoot           -> entry.name == "files" || entry.name == "no_backup"
                        isInsideFiles    -> entry.name !in filesExclusions
                        isInsideNoBackup -> entry.name !in noBackupExclusions
                        else             -> !entry.name.startsWith(".")
                    }
                }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        }
    }

    BackHandler {
        when {
            selectionMode -> { selectionMode = false; selectedIds = emptySet() }
            fabExpanded   -> fabExpanded = false
            else          -> showLockDialog = true
        }
    }

    val appStorageLabel   = stringResource(R.string.vault_storage_app)
    val localStorageLabel = stringResource(R.string.vault_storage_local)
    val usbStorageLabel   = stringResource(R.string.vault_storage_usb)
    val usbPartLabel      = stringResource(R.string.vault_storage_usb_partition)
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
                                // This never deleted anything on disk for any vault type -
                                // it removed the records and nothing else - so it is named
                                // for what it does, and now asks first, which a bin icon
                                // firing on one tap did not.
                                IconButton(
                                    onClick = { showForgetSelectedDialog = true },
                                    enabled = selectedIds.isNotEmpty()
                                ) {
                                    Icon(Icons.Outlined.LinkOff, contentDescription = stringResource(R.string.vault_cd_forget_selected))
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
                                    when {
                                        // A non-zero offset means the vault sits in a
                                        // partition, so the drive holds other things too.
                                        // Known from the record itself - no drive needed.
                                        c.usbSaltHash.isNotEmpty() ->
                                            if (c.usbStartByte > 0L) usbPartLabel else usbStorageLabel
                                        c.safUri.isEmpty() &&
                                            (c.path.startsWith(context.filesDir.absolutePath) ||
                                             c.path.startsWith(context.noBackupFilesDir.absolutePath)) -> appStorageLabel
                                        else -> localStorageLabel
                                    }
                                }.entries.sortedBy { it.key }
                                grouped.forEach { (groupName, groupList) ->
                                    stickyHeader(key = "hdr_$groupName") {
                                        Text(
                                            text       = groupName,
                                            style      = MaterialTheme.typography.labelLarge,
                                            color      = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier   = Modifier
                                                .fillMaxWidth()
                                                .background(MaterialTheme.colorScheme.surface)
                                                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
                                        )
                                    }
                                    itemsIndexed(groupList, key = { _, c -> c.id }) { index, container ->
                                        VaultCardItem(
                                            // Grouped by location, a group is one block of
                                            // cards - the shape the settings screens use, with
                                            // the round corners on the outside of the group
                                            // and near-square ones within it.
                                            cardShape              = groupShape(index, groupList.size),
                                            isLastInGroup          = index == groupList.size - 1,
                                            container              = container,
                                            selectedIds            = selectedIds,
                                            selectionMode          = selectionMode,
                                            contextMenuContainerId = contextMenuContainerId,
                                            onContextMenuChange    = { open -> contextMenuContainerId = if (open) container.id else null },
                                            onSelect               = { selectedIds = if (container.id in selectedIds) selectedIds - container.id else selectedIds + container.id },
                                            onOpen                 = { openVault(container) },
                                            onVaultInfo            = { onVaultInfo(container.id) },
                                            onRename               = { renameText = container.name; renameContainer = container },
                                            isMissing              = container.id in missingIds,
                                            onLongClick            = { selectionMode = true; selectedIds = selectedIds + container.id },
                                            onUnmount              = { containerToUnmount = container }
                                        )
                                    }
                                }
                            } else {
                                items(containers, key = { it.id }) { container ->
                                    VaultCardItem(
                                        container              = container,
                                        selectedIds            = selectedIds,
                                        selectionMode          = selectionMode,
                                        contextMenuContainerId = contextMenuContainerId,
                                        onContextMenuChange    = { open -> contextMenuContainerId = if (open) container.id else null },
                                        onSelect               = { selectedIds = if (container.id in selectedIds) selectedIds - container.id else selectedIds + container.id },
                                        onOpen                 = { openVault(container) },
                                        onVaultInfo            = { onVaultInfo(container.id) },
                                        onRename               = { renameText = container.name; renameContainer = container },
                                        isMissing              = container.id in missingIds,
                                        onLongClick            = { selectionMode = true; selectedIds = selectedIds + container.id },
                                        onUnmount              = { containerToUnmount = container }
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
                // "Generate keyfile" — top, appears last (100 ms delay).
                // Not gated by canAddMoreContainers: a keyfile is not a vault.
                AnimatedVisibility(
                    visible = fabExpanded,
                    enter   = slideInVertically(
                        animationSpec  = tween(300, delayMillis = 100),
                        initialOffsetY = { it / 2 }
                    ) + fadeIn(tween(200, delayMillis = 100)),
                    exit    = slideOutVertically(tween(160), targetOffsetY = { it / 2 }) + fadeOut(tween(150))
                ) {
                    FabMenuItem(
                        icon    = ArcanumIcons.Keyfile,
                        label   = stringResource(R.string.vault_fab_generate_keyfile),
                        onClick = {
                            fabExpanded = false
                            onGenerateKeyfile()
                        }
                    )
                }
                // "Open existing" — middle, appears second (50 ms delay)
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
                            if (canAddMoreContainers) showOpenVaultSheet = true
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
                        icon    = Icons.Outlined.EnhancedEncryption,
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

            // ── Unmount confirm dialog ────────────────────────────────────────
            containerToUnmount?.let { c ->
                val isUsb = c.usbSaltHash.isNotEmpty()
                AppDialog(
                    onDismissRequest = { containerToUnmount = null },
                    title            = {
                        Text(stringResource(
                            if (isUsb) R.string.vault_eject_title else R.string.vault_unmount_title,
                            c.name
                        ))
                    },
                    text             = {
                        Text(stringResource(if (isUsb) R.string.vault_eject_body else R.string.vault_unmount_body))
                    },
                    confirmButton    = {
                        TextButton(onClick = {
                            containerToUnmount = null
                            viewModel.unmountContainer(c.id) {
                                onUnmountStart(c.id)
                                // Tells the user the physical action is now safe. For a
                                // file vault there is nothing to unplug and nothing to say.
                                if (isUsb) notifications.notify(InAppNotification.UsbSafeToRemove(c.id, c.name))
                            }
                        }) {
                            Text(stringResource(if (isUsb) R.string.vault_eject_confirm else R.string.vault_unmount_confirm))
                        }
                    },
                    dismissButton    = {
                        TextButton(onClick = { containerToUnmount = null }) { Text(stringResource(R.string.common_cancel)) }
                    }
                )
            }

            // ── Forget the selection ─────────────────────────────────────────
            if (showForgetSelectedDialog && selectedIds.isNotEmpty()) {
                val n = selectedIds.size
                AppDialog(
                    onDismissRequest = { showForgetSelectedDialog = false },
                    title            = { Text(pluralStringResource(R.plurals.vault_forget_many_title, n, n)) },
                    text             = { Text(stringResource(R.string.vault_forget_many_body)) },
                    confirmButton    = {
                        TextButton(onClick = {
                            showForgetSelectedDialog = false
                            viewModel.deleteContainers(selectedIds)
                            selectionMode = false
                            selectedIds   = emptySet()
                        }) { Text(stringResource(R.string.vault_forget_confirm)) }
                    },
                    dismissButton    = {
                        TextButton(onClick = { showForgetSelectedDialog = false }) {
                            Text(stringResource(R.string.common_cancel))
                        }
                    }
                )
            }

            // ── Upgrade overlay ───────────────────────────────────────────────
            if (showUpgradeDialog) {
                UpgradeOverlay(onDismiss = { showUpgradeDialog = false })
            }

            // ── Rename ────────────────────────────────────────────────────────
            renameContainer?.let { target ->
                AppDialog(
                    onDismissRequest = { renameContainer = null },
                    title            = { Text(stringResource(R.string.vault_rename_title)) },
                    text             = {
                        Column {
                            OutlinedTextField(
                                value         = renameText,
                                onValueChange = { renameText = it },
                                label         = { Text(stringResource(R.string.vault_rename_label)) },
                                singleLine    = true,
                                modifier      = Modifier.fillMaxWidth()
                            )
                            if (target.safUri.isNotEmpty()) {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text  = stringResource(R.string.vault_rename_saf_note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    confirmButton    = {
                        TextButton(
                            onClick = { viewModel.renameContainer(target.id, renameText.trim()) },
                            enabled = renameText.isNotBlank() && renameText.trim() != target.name
                        ) { Text(stringResource(R.string.vault_rename_confirm)) }
                    },
                    dismissButton    = {
                        TextButton(onClick = { renameContainer = null }) {
                            Text(stringResource(R.string.common_cancel))
                        }
                    }
                )
            }

            // ── The file picked is not this volume ────────────────────────────
            pendingRelocate?.let { pending ->
                RelocateMismatchOverlay(
                    fileName    = pending.name,
                    onChooseAnother = {
                        val id = pending.id
                        viewModel.cancelRelocate()
                        relocatingId = id
                        relocateLauncher.launch(arrayOf("*/*"))
                    },
                    onUseAnyway = { viewModel.confirmRelocate() },
                    onBack      = { viewModel.cancelRelocate() }
                )
            }

            // ── USB drive missing ─────────────────────────────────────────────
            // The same question the vault's own screen asks, in the same words: a vault on a
            // drive is checked before the mount screen opens, not after a password is typed.
            if (showUsbMissing) {
                AppDialog(
                    onDismissRequest = { showUsbMissing = false; pendingUsbAction = null },
                    title            = { Text(stringResource(R.string.usb_not_connected_title)) },
                    text             = { Text(stringResource(R.string.usb_not_connected_body)) },
                    confirmButton    = {
                        TextButton(onClick = {
                            showUsbMissing = false
                            val retry = pendingUsbAction
                            usbScope.launch {
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
                        TextButton(onClick = { showUsbMissing = false; pendingUsbAction = null }) {
                            Text(stringResource(R.string.common_cancel))
                        }
                    }
                )
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
                    onLocate         = {
                        val target = containerNotFound
                        containerNotFound = null
                        if (target != null) {
                            relocatingId = target.id
                            relocateLauncher.launch(arrayOf("*/*"))
                        }
                    },
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

            // ── "plug the drive in" prompt ────────────────────────────────────
            // Shown whenever something needs the drive and it is not there. Retrying is
            // the whole interaction, so the action button repeats the request rather than
            // just dismissing.
            if (showUsbInsertPrompt) {
                AppDialog(
                    onDismissRequest = { showUsbInsertPrompt = false },
                    title            = { Text(stringResource(R.string.usb_not_connected_title)) },
                    text             = { Text(stringResource(R.string.usb_not_connected_body)) },
                    confirmButton    = {
                        TextButton(onClick = {
                            showUsbInsertPrompt = false
                            usbScope.launch {
                                if (viewModel.ensureUsbPermission()) viewModel.loadUsbLayout()
                                else showUsbInsertPrompt = true
                            }
                        }) { Text(stringResource(R.string.usb_try_again)) }
                    },
                    dismissButton    = {
                        TextButton(onClick = { showUsbInsertPrompt = false }) {
                            Text(stringResource(R.string.common_cancel))
                        }
                    }
                )
            }

            // ── Open vault source sheet ───────────────────────────────────────
            if (showOpenVaultSheet) {
                AppSheet(
                    onDismissRequest = { showOpenVaultSheet = false },
                    sheetState       = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ) {
                    Text(
                        text       = stringResource(R.string.vault_fab_open_existing),
                        style      = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier   = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                    ListItem(
                        colors          = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent  = { Icon(Icons.Outlined.PhoneAndroid, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.vault_storage_app)) },
                        trailingContent = { Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        modifier        = Modifier.clickable {
                            showOpenVaultSheet = false
                            showAppStoragePicker = true
                        }
                    )
                    ListItem(
                        colors          = ListItemDefaults.colors(containerColor = Color.Transparent),
                        /* The same icon internal storage has everywhere else: the create
                           wizard's step 2, and every vault card through vaultStorageIcon.
                           A folder here said "browse", which is not what it opens. */
                        leadingContent  = { Icon(Icons.Outlined.Storage, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.vault_storage_internal)) },
                        trailingContent = { Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        modifier        = Modifier.clickable {
                            showOpenVaultSheet = false
                            openDocumentLauncher.launch(arrayOf("*/*"))
                        }
                    )
                    // There is nothing to browse here, but there is something to choose:
                    // the drive may be partitioned, and the vault is in one of them (#131).
                    ListItem(
                        colors          = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent  = { Icon(Icons.Outlined.Usb, contentDescription = null) },
                        headlineContent = { Text(stringResource(R.string.vault_storage_usb)) },
                        supportingContent = { Text(stringResource(R.string.vault_storage_usb_hint)) },
                        trailingContent = { Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        modifier        = Modifier.clickable {
                            showOpenVaultSheet = false
                            usbScope.launch {
                                if (viewModel.ensureUsbPermission()) viewModel.loadUsbLayout()
                                else showUsbInsertPrompt = true
                            }
                        }
                    )
                    Spacer(Modifier.height(navBarPadding + 8.dp))
                }
            }

            // ── USB partition picker ──────────────────────────────────────────
            // Only reached when the drive carries a partition table. A bare drive skips
            // this, since "whole drive" would be the only thing to choose (#131).
            usbLayout?.let { layout ->
                val gpt = zip.arcanum.usb.isGptProtective(layout.partitions)
                AppSheet(
                    onDismissRequest = { viewModel.dismissUsbLayout() },
                    sheetState       = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ) {
                    Text(
                        text       = stringResource(R.string.usb_pick_title),
                        style      = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier   = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                    Text(
                        text     = stringResource(if (gpt) R.string.usb_gpt_note else R.string.usb_pick_hint),
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)
                    )
                    if (!gpt) {
                        // An extended entry is a container for logical partitions, not a
                        // place data lives, so offering it would only mislead.
                        layout.partitions.filterNot { it.isExtendedContainer() }.forEach { part ->
                            ListItem(
                                colors            = ListItemDefaults.colors(containerColor = Color.Transparent),
                                leadingContent    = { Icon(Icons.Outlined.Storage, contentDescription = null) },
                                headlineContent   = { Text(stringResource(R.string.usb_partition_n, part.slot + 1)) },
                                supportingContent = { Text("${part.typeName} - ${part.sizeBytes.fmtSize()}") },
                                modifier          = Modifier.clickable {
                                    viewModel.dismissUsbLayout()
                                    viewModel.addUsbContainer(part)
                                }
                            )
                        }
                    }
                    ListItem(
                        colors            = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent    = { Icon(Icons.Outlined.Usb, contentDescription = null) },
                        headlineContent   = { Text(stringResource(R.string.usb_whole_drive)) },
                        supportingContent = { Text("${layout.label} - ${layout.sizeBytes.fmtSize()}") },
                        modifier          = Modifier.clickable {
                            viewModel.dismissUsbLayout()
                            viewModel.addUsbContainer(null)
                        }
                    )
                    Spacer(Modifier.height(navBarPadding + 8.dp))
                }
            }

            // ── App Storage picker sheet ──────────────────────────────────────
            if (showAppStoragePicker) {
                val isAtRoot   = appStorageCurrentDir == appStorageRoot
                val relPath    = if (isAtRoot) "" else appStorageCurrentDir.toRelativeString(appStorageRoot)
                val breadcrumb = if (isAtRoot) stringResource(R.string.vault_storage_app)
                                 else stringResource(R.string.vault_storage_app) + " > " + relPath.replace("/", " > ")

                AppSheet(
                    onDismissRequest = {
                        showAppStoragePicker = false
                        appStorageCurrentDir = appStorageRoot
                    },
                    sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ) {
                    BackHandler(!isAtRoot) {
                        appStorageCurrentDir = appStorageCurrentDir.parentFile ?: appStorageRoot
                    }
                    Row(
                        modifier          = Modifier.fillMaxWidth().padding(end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!isAtRoot) {
                            IconButton(onClick = { appStorageCurrentDir = appStorageCurrentDir.parentFile ?: appStorageRoot }) {
                                Icon(Icons.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back))
                            }
                        } else {
                            Spacer(Modifier.width(16.dp))
                        }
                        Text(
                            text       = breadcrumb,
                            style      = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier   = Modifier.padding(vertical = 12.dp)
                        )
                    }
                    if (appStorageEntries.isEmpty()) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier         = Modifier.fillMaxWidth().height(120.dp)
                        ) {
                            Text(
                                text  = stringResource(R.string.vault_appstorage_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn {
                            items(appStorageEntries, key = { it.absolutePath }) { entry ->
                                ListItem(
                                    colors           = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    leadingContent   = {
                                        Icon(
                                            if (entry.isDirectory) Icons.Outlined.Folder else Icons.Outlined.Article,
                                            contentDescription = null,
                                            tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary
                                                   else MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    headlineContent  = { Text(entry.name) },
                                    supportingContent = if (!entry.isDirectory) ({
                                        Text(entry.length().fmtSize())
                                    }) else null,
                                    trailingContent  = if (entry.isDirectory) ({
                                        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }) else null,
                                    modifier = Modifier.clickable {
                                        if (entry.isDirectory) {
                                            appStorageCurrentDir = entry
                                        } else {
                                            viewModel.addContainerFromPath(entry.absolutePath)
                                            showAppStoragePicker = false
                                            appStorageCurrentDir = appStorageRoot
                                        }
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(navBarPadding + 8.dp))
                }
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

/** The gap between cards inside one location group - what SettingsGroup leaves. */
private val GROUP_GAP = 3.dp

@Composable
private fun VaultCardItem(
    container: ContainerEntity,
    cardShape: Shape? = null,
    onVaultInfo: () -> Unit = {},
    onRename: () -> Unit = {},
    isMissing: Boolean = false,
    isLastInGroup: Boolean = true,
    selectedIds: Set<String>,
    selectionMode: Boolean,
    contextMenuContainerId: String?,
    onContextMenuChange: (Boolean) -> Unit,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
    onLongClick: () -> Unit,
    onUnmount: () -> Unit,
) {
    VaultCard(
        container               = container,
        cardShape               = cardShape,
        isLastInGroup           = isLastInGroup,
        isSelected              = container.id in selectedIds,
        inSelectionMode         = selectionMode,
        onLockIconClick         = if (container.isMounted && !selectionMode) onUnmount else null,
        showContextMenu         = container.id == contextMenuContainerId,
        onShowContextMenuChange = onContextMenuChange,
        onClick                 = { if (selectionMode) onSelect() else onOpen() },
        onLongClick             = onLongClick,
        onUnmount               = onUnmount,
        onVaultInfo             = onVaultInfo,
        onRename                = onRename,
        isMissing               = isMissing
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
    /** Non-null while the list is grouped: where this row sits in its block of cards. */
    cardShape: Shape? = null,
    isLastInGroup: Boolean = true,
    isSelected: Boolean,
    inSelectionMode: Boolean,
    onLockIconClick: (() -> Unit)? = null,
    showContextMenu: Boolean = false,
    onShowContextMenuChange: (Boolean) -> Unit = {},
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onUnmount: () -> Unit = {},
    onVaultInfo: () -> Unit = {},
    onRename: () -> Unit = {},
    /** The file this vault lives in is not where it was: it can be pointed at one again. */
    isMissing: Boolean = false,
) {
    val context = LocalContext.current
    val appStr   = stringResource(R.string.vault_storage_app)
    val localStr = stringResource(R.string.vault_storage_local)
    val usbStr   = stringResource(R.string.vault_storage_usb)
    val usbPartStr = stringResource(R.string.vault_storage_usb_partition)
    val storageLabel = remember(
        container.path, container.safUri, container.usbSaltHash, container.usbStartByte,
        appStr, localStr, usbStr, usbPartStr
    ) {
        val p = container.path
        when {
            // Checked first: a USB vault has neither a path nor a SAF URI, so every
            // test below it would fall through to the local-storage default.
            container.usbSaltHash.isNotEmpty()                  ->
                if (container.usbStartByte > 0L) usbPartStr else usbStr
            p.startsWith(context.filesDir.absolutePath)         -> appStr
            p.startsWith(context.noBackupFilesDir.absolutePath) -> appStr
            else                                                -> localStr
        }
    }
    /*
     * Grouped by location, the group's own heading already says where the vault is, and
     * repeating it under every name is noise. The row says instead what the heading cannot:
     * when the vault was last opened. Android's own relative wording, so it follows the
     * phone's language and turns into a date once "days ago" stops meaning anything.
     */
    val openedLabel = if (cardShape != null && container.lastAccessedAt > 0L) {
        stringResource(
            R.string.vault_last_opened,
            remember(container.lastAccessedAt) {
                android.text.format.DateUtils.getRelativeTimeSpanString(
                    container.lastAccessedAt,
                    System.currentTimeMillis(),
                    android.text.format.DateUtils.MINUTE_IN_MILLIS
                ).toString()
            }
        )
    } else null

    val bgColor by animateColorAsState(
        targetValue   = when {
            isSelected      -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            cardShape != null -> MaterialTheme.colorScheme.surfaceContainerHigh
            else            -> Color.Transparent
        },
        animationSpec = tween<androidx.compose.ui.graphics.Color>(150),
        label         = "card_sel_bg"
    )
    val density     = LocalDensity.current
    var touchOffset by remember { mutableStateOf(DpOffset.Zero) }
    var menuWidthPx     by remember { mutableIntStateOf(0) }
    var cardHeightPx    by remember { mutableIntStateOf(0) }
    Box(
        modifier = if (cardShape != null)
            Modifier.padding(
                start  = 16.dp,
                end    = 16.dp,
                bottom = if (isLastInGroup) 0.dp else GROUP_GAP
            )
        else Modifier
    ) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .then(if (cardShape != null) Modifier.clip(cardShape) else Modifier)
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
            /* Three states, three colours: open is green, closed takes the accent, and a
               vault whose file is missing is grey - deliberately the dullest of the three,
               because it is not an alarm, it is a vault that is simply not there. */
            val iconBg by animateColorAsState(
                targetValue   = when {
                    isMissing            -> MaterialTheme.colorScheme.surfaceContainerHighest
                    container.isMounted  -> Color(0xFF16A34A)
                    else                 -> MaterialTheme.colorScheme.primaryContainer
                },
                animationSpec = tween<Color>(300),
                label         = "icon_bg"
            )
            val iconTint by animateColorAsState(
                targetValue   = when {
                    isMissing            -> MaterialTheme.colorScheme.onSurfaceVariant
                    container.isMounted  -> Color.White
                    else                 -> MaterialTheme.colorScheme.onPrimaryContainer
                },
                animationSpec = tween<Color>(300),
                label         = "icon_tint"
            )
            Surface(
                shape    = RoundedCornerShape(14.dp),
                color    = iconBg,
                modifier = Modifier
                    .size(52.dp)
                    .then(
                        if (onLockIconClick != null) Modifier.clickable(onClick = onLockIconClick)
                        else Modifier
                    )
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    /* Where the vault is kept, not a lock. Whether it is open is already
                       said by the colour behind this icon and by the row itself, and with
                       a list of vaults the useful distinction is USB from phone from
                       ordinary storage. Same rule as the vault's own screen and the
                       destination sheet. */
                    Icon(
                        imageVector        = if (isMissing) Icons.Outlined.FolderOff
                                             else vaultStorageIcon(
                                                 path        = container.path,
                                                 safUri      = container.safUri,
                                                 usbSaltHash = container.usbSaltHash
                                             ),
                        contentDescription = null,
                        tint               = iconTint,
                        modifier           = Modifier.size(26.dp)
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
            // Grouped: when it was last opened. Flat list: where it is kept.
            val secondLine = if (cardShape != null) openedLabel else storageLabel
            if (secondLine != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text  = secondLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // No size for a vault whose file is missing: the number would describe something the
        // app cannot see, and the row already says what is wrong through its icon.
        if (!inSelectionMode && !isMissing) {
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
        // Closing an open vault leads, because it is the one thing here that is urgent.
        if (container.isMounted) {
            DropdownMenuItem(
                text        = { Text(stringResource(R.string.vault_menu_unmount)) },
                leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                onClick     = { onShowContextMenuChange(false); onUnmount() }
            )
        }
        DropdownMenuItem(
            text        = { Text(stringResource(R.string.vault_menu_rename)) },
            leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null) },
            onClick     = { onShowContextMenuChange(false); onRename() }
        )
        DropdownMenuItem(
            text        = { Text(stringResource(R.string.vault_menu_select)) },
            leadingIcon = { Icon(Icons.Outlined.CheckBox, contentDescription = null) },
            onClick     = { onShowContextMenuChange(false); onLongClick() }
        )
        // Last: the vault's own page, which is where a tap on the card used to land.
        DropdownMenuItem(
            text        = { Text(stringResource(R.string.vault_menu_info)) },
            leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
            onClick     = { onShowContextMenuChange(false); onVaultInfo() }
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
    return when {
        // A USB-hosted vault has neither a path nor a SAF URI, so both checks below would
        // report it missing. What "accessible" means here is only that a drive is present:
        // whether it is the RIGHT drive costs a claim, and claiming merely to draw a list
        // row would eject the drive from Android every time this screen is opened. The
        // volume's identity is checked at mount time instead, which is the only moment it
        // matters.
        container.usbSaltHash.isNotEmpty() -> {
            val manager = context.getSystemService(android.content.Context.USB_SERVICE) as? android.hardware.usb.UsbManager
            manager?.deviceList?.values?.any {
                zip.arcanum.usb.UsbBlockDevice.massStorageInterface(it) != null
            } == true
        }
        container.safUri.isNotEmpty() -> {
            try {
                context.contentResolver.openFileDescriptor(android.net.Uri.parse(container.safUri), "r")?.use { true } ?: false
            } catch (_: Exception) { false }
        }
        else -> java.io.File(container.path).exists()
    }
}

@Composable
private fun ContainerNotFoundOverlay(
    container: ContainerEntity,
    onBack: () -> Unit,
    onLocate: () -> Unit,
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 40.dp)
                // What the buttons below occupy: the content is centred in what is left,
                // not in the screen, or it comes to rest on them.
                .padding(bottom = 180.dp),
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
            /* A vault goes missing for ordinary reasons - the file was moved, or the list
               came from another phone - and the useful thing to offer is the file, not the
               way out. A vault on a drive has no file to point at: what is missing there is
               the drive itself, and the answer is to plug it in. */
            if (container.usbSaltHash.isEmpty()) {
                Button(
                    onClick  = onLocate,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.vault_not_found_locate))
                }
            }
            TextButton(
                onClick  = onBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text  = stringResource(R.string.common_back),
                    color = Color.White.copy(alpha = 0.7f)
                )
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


/** The provider behind "Internal storage" in the system picker. */
private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

/**
 * [ActivityResultContracts.OpenDocument] that asks the picker to start somewhere useful
 * instead of Recent. The extra is a hint: a picker that does not honour it, or a provider
 * that is not present in this profile, simply lands where it would have anyway.
 */
private class OpenDocumentStartingAt(
    private val initialUri: android.net.Uri?
) : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: android.content.Context, input: Array<String>): android.content.Intent =
        super.createIntent(context, input).apply {
            if (initialUri != null) putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
        }
}

/**
 * The file somebody pointed a lost vault at is not the volume that vault means (#63).
 *
 * A refusal rather than a question, because the header's salt does answer this: the file
 * whose first 64 bytes hash to something else is a different volume. Esdex's call, and the
 * right default.
 *
 * The way out at the bottom is not a hedge. A salt changes whenever the header is rewritten,
 * so a vault whose password was changed on a desktop carries a fingerprint this phone has
 * never seen - the file is the right one and the app cannot tell. Without that button such a
 * vault could only be recovered by forgetting it and adding it again, losing every setting
 * it had, which is the very thing this screen exists to avoid.
 */
@Composable
private fun RelocateMismatchOverlay(
    fileName: String,
    onChooseAnother: () -> Unit,
    onUseAnyway: () -> Unit,
    onBack: () -> Unit
) {
    androidx.activity.compose.BackHandler { onBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(21f)
            .background(Color.Black)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication        = null
            ) {}
    ) {
        Column(
            modifier            = Modifier
                .align(Alignment.Center)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 40.dp)
                // What the buttons below occupy: the content is centred in what is left,
                // not in the screen, or it comes to rest on them.
                .padding(bottom = 180.dp),
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
                    Icons.Outlined.Warning,
                    contentDescription = null,
                    tint     = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(48.dp)
                )
            }
            Spacer(Modifier.height(32.dp))
            Text(
                text       = stringResource(R.string.vault_relocate_mismatch_title),
                style      = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color      = Color.White,
                textAlign  = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text      = stringResource(R.string.vault_relocate_mismatch_body, fileName),
                style     = MaterialTheme.typography.bodyMedium,
                color     = Color.White.copy(alpha = 0.7f),
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
                onClick  = onChooseAnother,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.vault_relocate_choose_another))
            }
            TextButton(
                onClick  = onUseAnyway,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text      = stringResource(R.string.vault_relocate_mismatch_confirm),
                    color     = Color.White.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center
                )
            }
            TextButton(
                onClick  = onBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text  = stringResource(R.string.common_back),
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
        }
    }
}
