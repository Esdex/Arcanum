package zip.arcanum.arcanum.containers.ui

import android.app.KeyguardManager
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import zip.arcanum.core.utils.FileUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
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
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.DeleteForever
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
import androidx.compose.material3.OutlinedTextField

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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
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
import zip.arcanum.core.components.SettingsSwitch
import zip.arcanum.core.database.entities.ContainerEntity
import zip.arcanum.core.notifications.InAppNotification
import zip.arcanum.core.notifications.InAppNotificationBanner
import zip.arcanum.crypto.VeraCryptEngine
import javax.crypto.Cipher
import java.text.DecimalFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

private sealed interface BioUiMode {
    data object Indicator : BioUiMode
    data object Cancelled : BioUiMode
    data object Form      : BioUiMode
}

private data class EncryptPending(
    val password: String,
    val pim: Int,
    val algorithm: Int,
    val hash: Int,
    val protectHidden: String?
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VaultScreen(
    onLock: () -> Unit,
    onCreateContainer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenContainer: (id: String) -> Unit,
    onMountSuccess: (id: String) -> Unit = {},
    onUnmountStart: (containerId: String) -> Unit = {},
    unmountedContainerId: String? = null,
    onUnmountedIconPositioned: (Offset) -> Unit = {},
    suppressBackHandler: Boolean = false,
    autoMountContainerId: String? = null,
    onAutoMountHandled: () -> Unit = {},
    onMoveVault: (containerId: String, toApp: Boolean) -> Unit = { _, _ -> },
    viewModel: VaultViewModel = hiltViewModel()
) {
    val context         = LocalContext.current
    val containers     by viewModel.containers.collectAsState()
    val mountState     by viewModel.mountState.collectAsState()
    val addVaultResult by viewModel.addVaultResult.collectAsState()
    val sortState      by viewModel.sortState.collectAsState()
    val hazeState      = remember { HazeState() }
    val isAmoled       = LocalAmoledMode.current
    val topBarColors   = if (isAmoled) TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                         else TopAppBarDefaults.topAppBarColors()
    val topBarHazeMod  = if (isAmoled) Modifier.hazeEffect(state = hazeState, style = ArcanumHazeStyle.topBar)
                         else Modifier

    var showSortSheet      by remember { mutableStateOf(false) }
    var fabExpanded        by remember { mutableStateOf(false) }
    var showLockDialog     by remember { mutableStateOf(false) }
    var showMountDialog    by remember { mutableStateOf(false) }
    var containerToMount   by remember { mutableStateOf<ContainerEntity?>(null) }
    var containerToUnmount        by remember { mutableStateOf<ContainerEntity?>(null) }
    var containerToRemoveFromList by remember { mutableStateOf<ContainerEntity?>(null) }
    var containerToDeleteFile     by remember { mutableStateOf<ContainerEntity?>(null) }
    var notification              by remember { mutableStateOf<InAppNotification?>(null) }
    var selectionMode      by remember { mutableStateOf(false) }
    var selectedIds        by remember { mutableStateOf(emptySet<String>()) }
    var isMountingOverlay        by remember { mutableStateOf(false) }
    var contextMenuContainerId   by remember { mutableStateOf<String?>(null) }
    var configContainer          by remember { mutableStateOf<ContainerEntity?>(null) }

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

    // Auto-open mount dialog when directed from creation wizard
    LaunchedEffect(autoMountContainerId, containers) {
        val id = autoMountContainerId ?: return@LaunchedEffect
        if (containers.isEmpty()) return@LaunchedEffect
        val entity = containers.find { it.id == id } ?: return@LaunchedEffect
        containerToMount = entity
        showMountDialog = true
        onAutoMountHandled()
    }

    // Convert add-vault result to notification
    LaunchedEffect(addVaultResult) {
        val result = addVaultResult ?: return@LaunchedEffect
        notification = when (result) {
            is VaultViewModel.AddVaultResult.Added        -> InAppNotification.VaultAdded(result.fileName)
            is VaultViewModel.AddVaultResult.AlreadyExists -> InAppNotification.VaultAlreadyExists(result.fileName)
            VaultViewModel.AddVaultResult.InvalidFile      -> InAppNotification.VaultInvalidFile
            is VaultViewModel.AddVaultResult.Error         -> InAppNotification.VaultAddError(result.message)
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
        if (uri != null) {
            val path = documentUriToPath(context, uri)
            if (path != null) {
                viewModel.addContainerFromPath(path)
            } else {
                notification = InAppNotification.VaultAddError("Cannot resolve file path")
            }
        }
    }

    var mountKeyfiles by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    val keyfilePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val (path, name) = FileUtils.copyUriToCache(context, uri) ?: return@rememberLauncherForActivityResult
        mountKeyfiles = mountKeyfiles + Pair(path, name)
    }

    LaunchedEffect(selectionMode) {
        if (selectionMode) { fabExpanded = false; contextMenuContainerId = null }
    }

    BackHandler(enabled = !suppressBackHandler) {
        when {
            selectionMode   -> { selectionMode = false; selectedIds = emptySet() }
            fabExpanded     -> fabExpanded = false
            showMountDialog -> { showMountDialog = false; viewModel.resetMountState(); mountKeyfiles.forEach { java.io.File(it.first).delete() }; mountKeyfiles = emptyList() }
            else            -> showLockDialog = true
        }
    }

    val appStorageLabel   = stringResource(R.string.vault_storage_app)
    val localStorageLabel = stringResource(R.string.vault_storage_local)

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
                                .padding(bottom = 80.dp)
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(
                                top    = innerPadding.calculateTopPadding(),
                                bottom = 80.dp
                            ),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            if (sortState.groupBy == VaultViewModel.GroupBy.LOCATION) {
                                val grouped = containers.groupBy { c ->
                                    val p = c.path
                                    if (p.startsWith(context.filesDir.absolutePath) ||
                                        p.startsWith(context.noBackupFilesDir.absolutePath))
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
                                            onOpen                 = { if (container.isMounted) onOpenContainer(container.id) else { containerToMount = container; showMountDialog = true } },
                                            onLongClick            = { selectionMode = true; selectedIds = selectedIds + container.id },
                                            onUnmount              = { containerToUnmount = container },
                                            onRemoveFromList       = { containerToRemoveFromList = container },
                                            onDeleteVault          = { containerToDeleteFile = container },
                                            onConfig               = { configContainer = container }
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
                                        onOpen                 = { if (container.isMounted) onOpenContainer(container.id) else { containerToMount = container; showMountDialog = true } },
                                        onLongClick            = { selectionMode = true; selectedIds = selectedIds + container.id },
                                        onUnmount              = { containerToUnmount = container },
                                        onRemoveFromList       = { containerToRemoveFromList = container },
                                        onDeleteVault          = { containerToDeleteFile = container },
                                        onConfig               = { configContainer = container }
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
                            openDocumentLauncher.launch(arrayOf("*/*"))
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
                            onCreateContainer()
                        }
                    )
                }
            }

            // ── Diamond FAB ───────────────────────────────────────────────────
            if (!selectionMode) Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
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
                onAction     = { notification = null },
                modifier     = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .zIndex(10f)
            )

            // ── Mount dialog ──────────────────────────────────────────────────
            if (showMountDialog && containerToMount != null) {
                val mountId = containerToMount!!.id
                MountDialog(
                    container    = containerToMount!!,
                    mountState   = mountState,
                    keyfiles     = mountKeyfiles,
                    biometricAvailable  = remember(mountId) { viewModel.isBiometricAvailable() },
                    hasBiometricSaved   = remember(mountId) { viewModel.hasBiometricCredentials(mountId) },
                    onGetEncryptCryptoObject = { viewModel.getBiometricCryptoObjectForEncrypt() },
                    onGetDecryptCryptoObject = { viewModel.getBiometricCryptoObjectForDecrypt(mountId) },
                    onSaveBiometricCredentials  = { cipher, pw, pim -> viewModel.saveBiometricCredentials(mountId, cipher, pw, pim) },
                    onDecryptBiometricCredentials = { cipher -> viewModel.decryptBiometricCredentials(mountId, cipher) },
                    onDeleteBiometricCredentials  = { viewModel.deleteBiometricCredentials(mountId) },
                    onAddKeyfile = { keyfilePickerLauncher.launch("*/*") },
                    onRemoveKeyfile = { index ->
                        val updated = mountKeyfiles.toMutableList()
                        java.io.File(updated[index].first).delete()
                        updated.removeAt(index)
                        mountKeyfiles = updated
                    },
                    onDismiss = {
                        showMountDialog = false
                        viewModel.resetMountState()
                        mountKeyfiles.forEach { java.io.File(it.first).delete() }
                        mountKeyfiles = emptyList()
                    },
                    onUnlock = { password, pim, algorithm, hashAlgorithm, protectHiddenPassword ->
                        showMountDialog = false
                        isMountingOverlay = true
                        viewModel.mountContainer(
                            container             = containerToMount!!,
                            password              = password,
                            keyfilePaths          = mountKeyfiles.map { it.first },
                            pim                   = pim,
                            algorithm             = algorithm,
                            hashAlgorithm         = hashAlgorithm,
                            protectHiddenPassword = protectHiddenPassword,
                            onSuccess             = { id ->
                                mountKeyfiles.forEach { java.io.File(it.first).delete() }
                                mountKeyfiles = emptyList()
                                isMountingOverlay = false
                                onMountSuccess(id)
                            }
                        )
                    }
                )
            }

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

            // ── Delete vault file confirm dialog ─────────────────────────────
            containerToDeleteFile?.let { c ->
                AppDialog(
                    onDismissRequest = { containerToDeleteFile = null },
                    title            = { Text(stringResource(R.string.vault_delete_title, c.name)) },
                    text             = { Text(stringResource(R.string.vault_delete_body)) },
                    confirmButton    = {
                        TextButton(onClick = {
                            containerToDeleteFile = null
                            viewModel.deleteVaultFile(c.id)
                        }) { Text(stringResource(R.string.vault_delete_confirm), color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton    = {
                        TextButton(onClick = { containerToDeleteFile = null }) { Text(stringResource(R.string.common_cancel)) }
                    }
                )
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

            // ── Per-vault config sheet ────────────────────────────────────────
            configContainer?.let { c ->
                AppSheet(
                    onDismissRequest = { configContainer = null },
                    sheetState       = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ) {
                    Column(modifier = Modifier.padding(bottom = 32.dp)) {
                        Text(
                            text     = c.name,
                            style    = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                        SettingsSwitch(
                            title           = stringResource(R.string.vault_config_unmount_on_lock),
                            subtitle        = stringResource(R.string.vault_config_unmount_on_lock_desc),
                            checked         = c.unmountOnLock,
                            onCheckedChange = {
                                viewModel.updateUnmountOnLock(c.id, it)
                                configContainer = c.copy(unmountOnLock = it)
                            }
                        )
                        SettingsSwitch(
                            title           = stringResource(R.string.vault_config_unmount_on_background),
                            subtitle        = stringResource(R.string.vault_config_unmount_on_background_desc),
                            checked         = c.unmountOnBackground,
                            onCheckedChange = {
                                viewModel.updateUnmountOnBackground(c.id, it)
                                configContainer = c.copy(unmountOnBackground = it)
                            }
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        val moveEnabled = !c.isMounted
                        val isInAppStorage = c.path.startsWith(context.filesDir.absolutePath) ||
                                             c.path.startsWith(context.noBackupFilesDir.absolutePath)

                        if (!isInAppStorage) {
                            androidx.compose.material3.ListItem(
                                headlineContent = { Text(stringResource(R.string.vault_config_move_to_app)) },
                                supportingContent = {
                                    Text(
                                        if (moveEnabled) stringResource(R.string.vault_config_move_to_app_desc)
                                        else stringResource(R.string.vault_config_unmount_to_move),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                },
                                trailingContent = {
                                    Icon(
                                        imageVector = androidx.compose.material.icons.Icons.Outlined.FolderOpen,
                                        contentDescription = null,
                                        tint = if (moveEnabled) MaterialTheme.colorScheme.primary
                                               else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    )
                                },
                                modifier = Modifier
                                    .then(
                                        if (moveEnabled) Modifier.clickable {
                                            configContainer = null
                                            onMoveVault(c.id, true)
                                        } else Modifier
                                    )
                            )
                        }

                        androidx.compose.material3.ListItem(
                            headlineContent = { Text(stringResource(R.string.vault_config_move_to_internal)) },
                            supportingContent = {
                                Text(
                                    if (moveEnabled) stringResource(R.string.vault_config_move_to_internal_desc)
                                    else stringResource(R.string.vault_config_unmount_to_move),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            trailingContent = {
                                Icon(
                                    imageVector = androidx.compose.material.icons.Icons.Outlined.FolderOpen,
                                    contentDescription = null,
                                    tint = if (moveEnabled) MaterialTheme.colorScheme.primary
                                           else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                )
                            },
                            modifier = Modifier
                                .then(
                                    if (moveEnabled) Modifier.clickable {
                                        configContainer = null
                                        onMoveVault(c.id, false)
                                    } else Modifier
                                )
                        )
                    }
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

            // ── Mounting overlay ──────────────────────────────────────────────
            AnimatedVisibility(
                visible  = isMountingOverlay,
                enter    = fadeIn(tween(300)),
                exit     = fadeOut(tween(300)),
                modifier = Modifier.zIndex(200f)
            ) {
                MountingOverlay(
                    isError   = mountState is VaultViewModel.MountState.Error,
                    onCancel  = {
                        viewModel.cancelMount()
                        isMountingOverlay = false
                        showMountDialog = true
                    },
                    onDismissError = {
                        viewModel.resetMountState()
                        isMountingOverlay = false
                    }
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
    onDeleteVault: () -> Unit,
    onConfig: () -> Unit,
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
        onRemoveFromList        = onRemoveFromList,
        onDeleteVault           = onDeleteVault,
        onConfig                = onConfig
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
        trailingContent = {
            Icon(
                imageVector        = if (direction == VaultViewModel.SortDirection.ASCENDING)
                                         Icons.Outlined.ArrowUpward
                                     else Icons.Outlined.ArrowDownward,
                contentDescription = if (direction == VaultViewModel.SortDirection.ASCENDING) stringResource(R.string.vault_sort_cd_ascending) else stringResource(R.string.vault_sort_cd_descending),
                tint               = if (isSelected) primary else subtle.copy(alpha = 0.35f),
                modifier           = Modifier.size(20.dp)
            )
        },
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

// ── MountDialog ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MountDialog(
    container: ContainerEntity,
    mountState: VaultViewModel.MountState,
    keyfiles: List<Pair<String, String>>,
    savedPim: Int = 0,
    biometricAvailable: Boolean = false,
    hasBiometricSaved: Boolean = false,
    onGetEncryptCryptoObject: () -> BiometricPrompt.CryptoObject? = { null },
    onGetDecryptCryptoObject: () -> BiometricPrompt.CryptoObject? = { null },
    onSaveBiometricCredentials: (Cipher, password: String, pim: Int) -> Unit = { _, _, _ -> },
    onDecryptBiometricCredentials: (Cipher) -> Pair<String, Int>? = { null },
    onDeleteBiometricCredentials: () -> Unit = {},
    onAddKeyfile: () -> Unit,
    onRemoveKeyfile: (index: Int) -> Unit,
    onDismiss: () -> Unit,
    onUnlock: (password: String, pim: Int, algorithm: Int, hashAlgorithm: Int, protectHiddenPassword: String?) -> Unit
) {
    // ── Existing form state ───────────────────────────────────────────
    var password           by rememberSaveable { mutableStateOf("") }
    var showPassword       by remember { mutableStateOf(false) }
    var algorithmExpanded  by remember { mutableStateOf(false) }
    var hashExpanded       by remember { mutableStateOf(false) }
    var selectedAlgorithm  by rememberSaveable { mutableIntStateOf(VeraCryptEngine.ALGO_AUTO) }
    var selectedHash       by rememberSaveable { mutableIntStateOf(VeraCryptEngine.HASH_AUTO) }
    var showAdvanced       by remember { mutableStateOf(savedPim > 0) }
    var pimValue           by rememberSaveable { mutableStateOf(if (savedPim > 0) savedPim.toString() else "") }
    var protectHidden      by remember { mutableStateOf(false) }
    var hiddenPassword     by remember { mutableStateOf("") }
    var showHiddenPassword by remember { mutableStateOf(false) }
    var shakeKey           by remember { mutableIntStateOf(0) }
    val shakeAnim          = remember { Animatable(0f) }

    // ── Biometric state ───────────────────────────────────────────────
    val bioModeState          = remember { mutableStateOf(if (hasBiometricSaved) BioUiMode.Indicator else BioUiMode.Form) }
    var bioMode               by bioModeState
    val biometricEnabledState = remember { mutableStateOf(hasBiometricSaved) }
    var biometricEnabled      by biometricEnabledState
    var localHasBiometricSaved by remember { mutableStateOf(hasBiometricSaved) }
    val isDecryptModeState    = remember { mutableStateOf(false) }
    val pendingEncryptState   = remember { mutableStateOf<EncryptPending?>(null) }
    var showRemoveBioDialog   by remember { mutableStateOf(false) }

    // ── Biometric prompt setup ────────────────────────────────────────
    val activity            = LocalContext.current as FragmentActivity
    val latestOnUnlock      = rememberUpdatedState(onUnlock)
    val latestOnSaveBio     = rememberUpdatedState(onSaveBiometricCredentials)
    val latestOnDecryptBio  = rememberUpdatedState(onDecryptBiometricCredentials)

    val biometricCallback = remember {
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val cipher = result.cryptoObject?.cipher ?: return
                if (isDecryptModeState.value) {
                    val creds = latestOnDecryptBio.value(cipher)
                    if (creds == null) {
                        bioModeState.value          = BioUiMode.Cancelled
                        biometricEnabledState.value = false
                        return
                    }
                    latestOnUnlock.value(creds.first, creds.second, VeraCryptEngine.ALGO_AUTO, VeraCryptEngine.HASH_AUTO, null)
                } else {
                    val data = pendingEncryptState.value ?: return
                    latestOnSaveBio.value(cipher, data.password, data.pim)
                    latestOnUnlock.value(data.password, data.pim, data.algorithm, data.hash, data.protectHidden)
                    pendingEncryptState.value = null
                }
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (isDecryptModeState.value) {
                    bioModeState.value          = BioUiMode.Cancelled
                    biometricEnabledState.value = false
                } else {
                    // User cancelled saving → mount without saving
                    pendingEncryptState.value?.let { data ->
                        latestOnUnlock.value(data.password, data.pim, data.algorithm, data.hash, data.protectHidden)
                    }
                    pendingEncryptState.value = null
                }
            }
            override fun onAuthenticationFailed() {}
        }
    }
    val biometricPrompt = remember {
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), biometricCallback)
    }

    val bioUnlockTitle    = stringResource(R.string.vault_biometric_unlock_title, container.name)
    val bioUnlockSubtitle = stringResource(R.string.vault_biometric_unlock_subtitle)
    val bioUsePassword    = stringResource(R.string.vault_biometric_use_password)
    val bioSaveTitle      = stringResource(R.string.vault_biometric_save_title)
    val bioSaveSubtitle   = stringResource(R.string.vault_biometric_save_subtitle)
    val bioSkip           = stringResource(R.string.vault_biometric_skip)

    // Auto-show biometric on open when credentials are saved
    LaunchedEffect(Unit) {
        if (!hasBiometricSaved) return@LaunchedEffect
        val cryptoObj = onGetDecryptCryptoObject()
        if (cryptoObj == null) {
            bioModeState.value          = BioUiMode.Cancelled
            biometricEnabledState.value = false
            return@LaunchedEffect
        }
        isDecryptModeState.value = true
        biometricPrompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(bioUnlockTitle)
                .setSubtitle(bioUnlockSubtitle)
                .setNegativeButtonText(bioUsePassword)
                .build(),
            cryptoObj
        )
    }

    LaunchedEffect(mountState) {
        if (mountState is VaultViewModel.MountState.Error) shakeKey++
    }
    LaunchedEffect(shakeKey) {
        if (shakeKey > 0) {
            repeat(3) { shakeAnim.animateTo(8f, tween(40)); shakeAnim.animateTo(-8f, tween(40)) }
            shakeAnim.animateTo(0f, tween(40))
        }
    }

    val isError   = mountState is VaultViewModel.MountState.Error
    val isLoading = mountState is VaultViewModel.MountState.Loading
    val pim       = pimValue.toIntOrNull() ?: 0

    // ── Remove biometric confirmation dialog ──────────────────────────
    if (showRemoveBioDialog) {
        AppDialog(
            onDismissRequest = { showRemoveBioDialog = false; biometricEnabled = true },
            title            = { Text(stringResource(R.string.vault_remove_biometric_title)) },
            text             = { Text(stringResource(R.string.vault_remove_biometric_body, container.name)) },
            confirmButton    = {
                TextButton(onClick = {
                    showRemoveBioDialog = false
                    onDeleteBiometricCredentials()
                    localHasBiometricSaved = false
                    biometricEnabled       = false
                    bioMode                = BioUiMode.Form
                }) { Text(stringResource(R.string.vault_remove_confirm)) }
            },
            dismissButton    = {
                TextButton(onClick = { showRemoveBioDialog = false; biometricEnabled = true }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
        return
    }

    AppDialog(
        onDismissRequest = onDismiss,
        title            = { Text(container.name) },
        text             = {
            when (bioMode) {

                // ── Biometric indicator (auto-prompt active) ──────────
                BioUiMode.Indicator -> {
                    Column(
                        modifier            = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier         = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.Fingerprint,
                                contentDescription = null,
                                tint     = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                        Text(
                            stringResource(R.string.vault_biometric_indicator),
                            style     = MaterialTheme.typography.bodyMedium,
                            color     = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // ── Biometric cancelled / failed ──────────────────────
                BioUiMode.Cancelled -> {
                    Column(
                        modifier            = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            stringResource(R.string.vault_biometric_failed),
                            style     = MaterialTheme.typography.bodyMedium,
                            color     = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // ── Normal password form ──────────────────────────────
                BioUiMode.Form -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value                = password,
                            onValueChange        = { password = it },
                            label                = { Text(stringResource(R.string.common_password)) },
                            singleLine           = true,
                            isError              = isError,
                            supportingText       = if (isError) { { Text(stringResource(R.string.vault_mount_wrong_password)) } } else null,
                            visualTransformation = if (showPassword) VisualTransformation.None
                                                   else PasswordVisualTransformation(),
                            trailingIcon         = {
                                IconButton(onClick = { showPassword = !showPassword }) {
                                    Icon(
                                        if (showPassword) Icons.Outlined.VisibilityOff
                                        else Icons.Outlined.Visibility,
                                        contentDescription = null
                                    )
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = { if ((password.isNotEmpty() || keyfiles.isNotEmpty()) && !isLoading) onUnlock(password, pim, selectedAlgorithm, selectedHash, if (protectHidden && hiddenPassword.isNotBlank()) hiddenPassword else null) }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .offset { IntOffset(shakeAnim.value.roundToInt(), 0) }
                        )

                        val algorithms = listOf(-1 to "Auto") + (0..14).map { id ->
                            id to VeraCryptEngine.algorithmIdToString(id).replace("-256-XTS", "")
                        }
                        val hashes = listOf(-1 to "Auto") + (0..3).map { it to VeraCryptEngine.hashIdToString(it) }

                        Row(
                            modifier              = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                OutlinedTextField(
                                    value         = algorithms.first { it.first == selectedAlgorithm }.second,
                                    onValueChange = {},
                                    readOnly      = true,
                                    label         = { Text(stringResource(R.string.vault_mount_algorithm)) },
                                    trailingIcon  = {
                                        Icon(
                                            if (algorithmExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                            contentDescription = null
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Box(Modifier.matchParentSize().clickable { algorithmExpanded = !algorithmExpanded })
                                DropdownMenu(
                                    expanded         = algorithmExpanded,
                                    onDismissRequest = { algorithmExpanded = false }
                                ) {
                                    algorithms.forEach { (id, label) ->
                                        DropdownMenuItem(
                                            text    = { Text(label, style = MaterialTheme.typography.bodySmall) },
                                            onClick = { selectedAlgorithm = id; algorithmExpanded = false }
                                        )
                                    }
                                }
                            }

                            Box(modifier = Modifier.weight(1f)) {
                                OutlinedTextField(
                                    value         = hashes.first { it.first == selectedHash }.second,
                                    onValueChange = {},
                                    readOnly      = true,
                                    label         = { Text(stringResource(R.string.vault_mount_hash)) },
                                    trailingIcon  = {
                                        Icon(
                                            if (hashExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                            contentDescription = null
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Box(Modifier.matchParentSize().clickable { hashExpanded = !hashExpanded })
                                DropdownMenu(
                                    expanded         = hashExpanded,
                                    onDismissRequest = { hashExpanded = false }
                                ) {
                                    hashes.forEach { (id, label) ->
                                        DropdownMenuItem(
                                            text    = { Text(label, style = MaterialTheme.typography.bodySmall) },
                                            onClick = { selectedHash = id; hashExpanded = false }
                                        )
                                    }
                                }
                            }
                        }

                        // Biometric toggle
                        if (biometricAvailable) {
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            Row(
                                modifier          = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Outlined.Fingerprint,
                                    contentDescription = null,
                                    tint     = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(R.string.vault_mount_biometric_toggle),
                                    style    = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked         = biometricEnabled,
                                    onCheckedChange = { newValue ->
                                        if (!newValue && localHasBiometricSaved) {
                                            showRemoveBioDialog = true
                                        } else {
                                            biometricEnabled = newValue
                                        }
                                    }
                                )
                            }
                        }

                        // Advanced section (PIM + keyfiles + hidden)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showAdvanced = !showAdvanced }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.vault_mount_advanced),
                                style    = MaterialTheme.typography.labelMedium,
                                color    = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                if (showAdvanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                contentDescription = null,
                                tint     = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        AnimatedVisibility(
                            visible = showAdvanced,
                            enter   = expandVertically(),
                            exit    = shrinkVertically()
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value         = pimValue,
                                    onValueChange = {
                                        if (it.all { c -> c.isDigit() } && it.length <= 7) pimValue = it
                                    },
                                    label                = { Text(stringResource(R.string.vault_mount_pim_label)) },
                                    placeholder          = { Text(stringResource(R.string.vault_mount_pim_placeholder)) },
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.NumberPassword,
                                        imeAction    = ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onDone = { if ((password.isNotEmpty() || keyfiles.isNotEmpty()) && !isLoading) onUnlock(password, pim, selectedAlgorithm, selectedHash, if (protectHidden && hiddenPassword.isNotBlank()) hiddenPassword else null) }
                                    ),
                                    singleLine = true,
                                    modifier   = Modifier.fillMaxWidth()
                                )
                                keyfiles.forEachIndexed { index, (_, displayName) ->
                                    Row(
                                        modifier          = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(displayName, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                        IconButton(onClick = { onRemoveKeyfile(index) }, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Outlined.Close, null, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                                TextButton(onClick = onAddKeyfile, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Outlined.Lock, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.vault_mount_add_keyfile), style = MaterialTheme.typography.labelMedium)
                                }
                                Row(
                                    modifier          = Modifier
                                        .fillMaxWidth()
                                        .clickable { protectHidden = !protectHidden }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Checkbox(
                                        checked         = protectHidden,
                                        onCheckedChange = { protectHidden = it },
                                        modifier        = Modifier.size(20.dp)
                                    )
                                    Column {
                                        Text(stringResource(R.string.vault_mount_protect_hidden), style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            stringResource(R.string.vault_mount_protect_hidden_desc),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                AnimatedVisibility(visible = protectHidden) {
                                    OutlinedTextField(
                                        value                = hiddenPassword,
                                        onValueChange        = { hiddenPassword = it },
                                        label                = { Text(stringResource(R.string.vault_mount_hidden_password)) },
                                        singleLine           = true,
                                        visualTransformation = if (showHiddenPassword) VisualTransformation.None
                                                               else PasswordVisualTransformation(),
                                        trailingIcon         = {
                                            IconButton(onClick = { showHiddenPassword = !showHiddenPassword }) {
                                                Icon(
                                                    if (showHiddenPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                                    contentDescription = null
                                                )
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (bioMode) {
                BioUiMode.Indicator -> { /* biometric prompt handles auth */ }
                BioUiMode.Cancelled -> {
                    TextButton(onClick = {
                        val cryptoObj = onGetDecryptCryptoObject()
                        if (cryptoObj != null) {
                            isDecryptModeState.value = true
                            biometricPrompt.authenticate(
                                BiometricPrompt.PromptInfo.Builder()
                                    .setTitle(bioUnlockTitle)
                                    .setSubtitle(bioUnlockSubtitle)
                                    .setNegativeButtonText(bioUsePassword)
                                    .build(),
                                cryptoObj
                            )
                        }
                    }) {
                        Text(stringResource(R.string.vault_biometric_try_again))
                    }
                }
                BioUiMode.Form -> {
                    val canUnlock = (password.isNotEmpty() || keyfiles.isNotEmpty()) && !isLoading
                    val protectedPassword = if (protectHidden && hiddenPassword.isNotBlank()) hiddenPassword else null
                    TextButton(
                        onClick = {
                            if (canUnlock) {
                                if (biometricEnabled && !localHasBiometricSaved) {
                                    val cryptoObj = onGetEncryptCryptoObject()
                                    if (cryptoObj != null) {
                                        isDecryptModeState.value  = false
                                        pendingEncryptState.value = EncryptPending(
                                            password      = password,
                                            pim           = pim,
                                            algorithm     = selectedAlgorithm,
                                            hash          = selectedHash,
                                            protectHidden = protectedPassword
                                        )
                                        biometricPrompt.authenticate(
                                            BiometricPrompt.PromptInfo.Builder()
                                                .setTitle(bioSaveTitle)
                                                .setSubtitle(bioSaveSubtitle)
                                                .setNegativeButtonText(bioSkip)
                                                .build(),
                                            cryptoObj
                                        )
                                    } else {
                                        onUnlock(password, pim, selectedAlgorithm, selectedHash, protectedPassword)
                                    }
                                } else {
                                    onUnlock(password, pim, selectedAlgorithm, selectedHash, protectedPassword)
                                }
                            }
                        },
                        enabled = canUnlock
                    ) {
                        if (isLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(stringResource(R.string.vault_biometric_unlock_confirm))
                    }
                }
            }
        },
        dismissButton = {
            when (bioMode) {
                BioUiMode.Cancelled -> TextButton(onClick = { bioMode = BioUiMode.Form }) { Text(stringResource(R.string.vault_biometric_use_password)) }
                else -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        }
    )
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
    onDeleteVault: () -> Unit,
    onConfig: () -> Unit,
) {
    val context = LocalContext.current
    val appStr   = stringResource(R.string.vault_storage_app)
    val localStr = stringResource(R.string.vault_storage_local)
    val storageLabel = remember(container.path, appStr, localStr) {
        val p = container.path
        when {
            p.startsWith(context.filesDir.absolutePath)         -> appStr
            p.startsWith(context.noBackupFilesDir.absolutePath) -> appStr
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
            text        = { Text(stringResource(R.string.vault_menu_config)) },
            leadingIcon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
            onClick     = { onShowContextMenuChange(false); onConfig() }
        )
        DropdownMenuItem(
            text        = { Text(stringResource(R.string.vault_menu_remove)) },
            leadingIcon = { Icon(Icons.Outlined.RemoveCircleOutline, contentDescription = null) },
            onClick     = { onShowContextMenuChange(false); onRemoveFromList() }
        )
        HorizontalDivider()
        DropdownMenuItem(
            text        = { Text(stringResource(R.string.vault_menu_delete), color = MaterialTheme.colorScheme.error) },
            leadingIcon = {
                Icon(
                    imageVector        = Icons.Outlined.DeleteForever,
                    contentDescription = null,
                    tint               = MaterialTheme.colorScheme.error
                )
            },
            onClick = { onShowContextMenuChange(false); onDeleteVault() }
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

private fun documentUriToPath(context: Context, uri: Uri): String? {
    return try {
        if (uri.scheme == "file") {
            uri.path
        } else if (!DocumentsContract.isDocumentUri(context, uri)) {
            queryDataColumn(context, uri)
        } else {
            val docId = DocumentsContract.getDocumentId(uri)
            when (uri.authority) {
                "com.android.externalstorage.documents" -> {
                    val split = docId.split(":", limit = 2)
                    if (split.size == 2 && split[0].equals("primary", ignoreCase = true)) {
                        "${Environment.getExternalStorageDirectory().absolutePath}/${split[1]}"
                    } else {
                        queryDataColumn(context, uri)
                    }
                }
                "com.android.providers.downloads.documents" -> when {
                    docId.startsWith("raw:") -> docId.removePrefix("raw:")
                    docId.startsWith("msd:") -> {
                        val id = docId.removePrefix("msd:").toLongOrNull()
                        if (id != null) queryDataColumn(context,
                            ContentUris.withAppendedId(Uri.parse("content://downloads/public_downloads"), id))
                        else queryDataColumn(context, uri)
                    }
                    else -> {
                        val id = docId.toLongOrNull()
                        if (id != null) queryDataColumn(context,
                            ContentUris.withAppendedId(Uri.parse("content://downloads/public_downloads"), id))
                        else queryDataColumn(context, uri)
                    }
                }
                else -> queryDataColumn(context, uri)
            }
        }
    } catch (_: Exception) { null }
}

private fun queryDataColumn(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val col = cursor.getColumnIndex("_data")
            if (col >= 0) cursor.getString(col) else null
        } else null
    }
} catch (_: Exception) { null }
