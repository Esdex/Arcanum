package zip.arcanum.arcanum.containers.ui

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import zip.arcanum.core.utils.FileUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import zip.arcanum.R
import zip.arcanum.core.components.AppDialog
import zip.arcanum.core.components.LocalHazeState
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import androidx.compose.runtime.CompositionLocalProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateContainerScreen(
    onBack: () -> Unit = {},
    onOpenVault: (containerId: String) -> Unit = {},
    viewModel: CreateContainerViewModel = hiltViewModel()
) {
    val context       = LocalContext.current
    val hazeState     = remember { HazeState() }

    val state              by viewModel.state.collectAsState()
    val createdContainerId by viewModel.createdContainerId.collectAsState()
    var prevStep           by remember { mutableIntStateOf(1) }
    var showCancelDialog   by remember { mutableStateOf(false) }

    // usbDataSizeBytes is a key, not just an input: the drive is measured
    // asynchronously after the location is chosen, and without it this stays at the
    // zero it was computed with and Next never enables.
    val availableSpaceMb = remember(state.filePath, state.location, state.usbDataSizeBytes) {
        try {
            if (state.location == StorageLocation.USB_DRIVE) {
                // Not a filesystem to stat: the whole drive is the volume, and its
                // usable size was measured when the drive was detected.
                state.usbDataSizeBytes / (1024L * 1024L)
            } else {
                val path = when (state.location) {
                    StorageLocation.APP_STORAGE      -> state.filePath
                    StorageLocation.INTERNAL_STORAGE -> Environment.getExternalStorageDirectory().absolutePath
                    StorageLocation.USB_DRIVE        -> ""   // handled above
                }
                StatFs(path).availableBytes / (1024L * 1024L)
            }
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }

    val fileCreatorLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        viewModel.setSafUri(uri)
    }

    val keyfilePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val (bytes, name) = FileUtils.readKeyfileBytes(context, uri) ?: return@rememberLauncherForActivityResult
        viewModel.addKeyfile(bytes, name)
    }

    val hiddenKeyfilePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val (bytes, name) = FileUtils.readKeyfileBytes(context, uri) ?: return@rememberLauncherForActivityResult
        viewModel.addHiddenKeyfile(bytes, name)
    }

    // Generated keyfiles need a folder to land in, so these pick a tree rather
    // than an existing document.
    val keyfileFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        viewModel.generateKeyfile(uri)
    }
    val hiddenKeyfileFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        viewModel.generateKeyfile(uri, hidden = true)
    }

    BackHandler {
        when {
            state.currentStep in listOf(10, 16) && state.isCreating -> showCancelDialog = true
            state.currentStep == 11 && state.volumeType == VolumeType.STANDARD -> onBack()
            state.currentStep == 11 && state.volumeType == VolumeType.HIDDEN   -> { /* locked: outer already created */ }
            state.currentStep in 12..15 -> viewModel.prevStep()
            state.currentStep == 3 && state.usbNewPartitionStep -> viewModel.cancelUsbNewPartition()
            state.currentStep in listOf(16, 17) -> { /* locked after hidden creation starts */ }
            state.currentStep > 1   -> viewModel.prevStep()
            else                    -> onBack()
        }
    }

    // Register container in the repo once outer creation is done (normal volume)
    LaunchedEffect(state.isCreated) {
        if (state.isCreated && state.volumeType == VolumeType.STANDARD) {
            viewModel.registerCreatedContainer()
        }
    }
    // Register container in the repo once hidden creation is done (hidden volume)
    LaunchedEffect(state.isHiddenCreated) {
        if (state.isHiddenCreated) viewModel.registerCreatedContainer()
    }

    LaunchedEffect(state.currentStep) {
        if (state.currentStep == 10 && !state.isCreating && !state.isCreated) {
            viewModel.startCreation()
        }
        if (state.currentStep == 16 && !state.isCreating && !state.isHiddenCreated) {
            viewModel.startHiddenCreation()
        }
        prevStep = state.currentStep
    }

    CompositionLocalProvider(LocalHazeState provides hazeState) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color    = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .hazeSource(hazeState)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── Top bar ────────────────────────────────────────────────
                val showTopBar = state.currentStep < 10 ||
                    (state.volumeType == VolumeType.HIDDEN && state.currentStep in 11..15)
                if (showTopBar) {
                    Row(
                        modifier          = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                when {
                                    state.currentStep in 11..15 &&
                                    state.volumeType == VolumeType.HIDDEN &&
                                    state.currentStep == 11 -> { /* locked */ }
                                    state.currentStep in 12..15 -> viewModel.prevStep()
                                    state.currentStep == 3 && state.usbNewPartitionStep -> viewModel.cancelUsbNewPartition()
                                    state.currentStep > 1 -> viewModel.prevStep()
                                    else                  -> onBack()
                                }
                            }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text       = stringResource(R.string.create_title),
                            style      = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier   = Modifier.weight(1f)
                        )
                        Text(
                            text     = stringResource(
                                R.string.create_step_counter,
                                state.displayStep, state.displayTotal - 1
                            ),
                            style    = MaterialTheme.typography.bodySmall,
                            color    = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 16.dp)
                        )
                    }
                }

                // ── Progress bar ──────────────────────────────────────────────
                if (showTopBar) {
                    LinearProgressIndicator(
                        progress   = { (state.displayStep - 1) / (state.displayTotal - 2f) },
                        modifier   = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .height(4.dp),
                        strokeCap  = StrokeCap.Round,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                }

                // ── Step content ──────────────────────────────────────────────
                AnimatedContent(
                    targetState   = state.currentStep,
                    transitionSpec = {
                        val forward = targetState >= initialState
                        val enter = slideInHorizontally(spring()) { if (forward) it else -it }
                        val exit  = slideOutHorizontally(spring()) { if (forward) -it else it }
                        enter togetherWith exit
                    },
                    label    = "wizard_step",
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 20.dp)
                ) { step ->
                    when (step) {
                        1    -> StepVolumeType(state, viewModel::update)
                        2    -> StepVolumeLocation(
                                    state                    = state,
                                    appStoragePath           = viewModel.appStoragePath,
                                    appStoragePathWithBackup = viewModel.appStoragePathWithBackup,
                                    onUpdate                 = viewModel::update,
                                    onBrowse                 = { viewModel.deletePendingSafFile(); fileCreatorLauncher.launch(state.fileName) },
                                    onClearSaf               = viewModel::clearSafUri,
                                    onDetectUsb              = viewModel::detectUsbDrive
                                )
                        3    -> StepUsbPartitions(
                                    state         = state,
                                    onSelect      = { start, size ->
                                        viewModel.selectUsbTarget(state.usbDeviceLabel, start, size)
                                    },
                                    onBeginNew      = viewModel::beginUsbNewPartition,
                                    onCancelNew     = viewModel::cancelUsbNewPartition,
                                    onSetForVault   = viewModel::setUsbNewForVault,
                                    onCreate        = viewModel::createUsbPartition,
                                    onRequestWholeDrive = viewModel::requestUsbWholeDrive,
                                    onConfirmWholeDrive = viewModel::confirmUsbWholeDrive,
                                    onCancelWholeDrive  = viewModel::cancelUsbWholeDrive,
                                    onRequestDelete = viewModel::requestUsbPartitionDelete,
                                    onConfirmDelete = viewModel::confirmUsbPartitionDelete,
                                    onCancelDelete  = viewModel::cancelUsbPartitionDelete
                                )
                        4    -> StepEncryptionAlgorithm(state, viewModel::update)
                        5    -> StepVolumeSize(state, viewModel::update, availableSpaceMb)
                        6    -> StepPassword(
                                    state             = state,
                                    onUpdate          = viewModel::update,
                                    onAddKeyfile      = { keyfilePickerLauncher.launch("*/*") },
                                    onGenerateKeyfile = { keyfileFolderLauncher.launch(null) },
                                    onRemoveKeyfile   = viewModel::removeKeyfile
                                )
                        7    -> StepFormatMode(state, viewModel::update)
                        8    -> StepFilesystem(state, viewModel::update)
                        9    -> StepEntropy(state, viewModel::addEntropyPoint)
                        10   -> StepCreating(state)
                        11   -> if (state.volumeType == VolumeType.HIDDEN) {
                                    StepHiddenInfo(state)
                                } else {
                                    StepSuccess(
                                        state,
                                        onDone      = onBack,
                                        onOpenVault = {
                                            val id = createdContainerId
                                            if (id != null) onOpenVault(id) else onBack()
                                        }
                                    )
                                }
                        12   -> StepHiddenAlgorithm(state, viewModel::update)
                        13   -> StepHiddenSize(state, viewModel::update)
                        14   -> StepHiddenPassword(
                                    state             = state,
                                    onUpdate          = viewModel::update,
                                    onAddKeyfile      = { hiddenKeyfilePickerLauncher.launch("*/*") },
                                    onGenerateKeyfile = { hiddenKeyfileFolderLauncher.launch(null) },
                                    onRemoveKeyfile   = viewModel::removeHiddenKeyfile
                                )
                        15   -> StepHiddenEntropy(state, viewModel::addHiddenEntropyPoint)
                        16   -> StepCreatingHidden(state)
                        17   -> StepSuccessHidden(
                                    state,
                                    onDone      = onBack,
                                    onOpenVault = {
                                        val id = createdContainerId
                                        if (id != null) onOpenVault(id) else onBack()
                                    }
                                )
                        else -> Box(Modifier.fillMaxSize())
                    }
                }

                // ── Next / Create button ──────────────────────────────────────
                val showNextButton = state.currentStep < 10 ||
                    (state.volumeType == VolumeType.HIDDEN && state.currentStep in 11..15)
                if (showNextButton) {
                    Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                        val buttonLabel = when {
                            state.currentStep == 9 && state.volumeType == VolumeType.HIDDEN   -> stringResource(R.string.create_btn_outer)
                            state.currentStep == 9 && state.volumeType == VolumeType.STANDARD -> stringResource(R.string.create_btn_create)
                            state.currentStep == 15 -> stringResource(R.string.create_btn_hidden)
                            else                    -> stringResource(R.string.create_btn_next)
                        }
                        Button(
                            onClick  = viewModel::nextStep,
                            enabled  = isStepValid(state, availableSpaceMb),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape    = CircleShape
                        ) {
                            Text(
                                text       = buttonLabel,
                                style      = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // ── Cancel during creation ────────────────────────────────────
                if (state.currentStep == 9 || state.currentStep == 15) {
                    Box(
                        modifier         = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        TextButton(onClick = { showCancelDialog = true }) {
                            Text(stringResource(R.string.create_cancel), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            // ── USB drive missing ─────────────────────────────────────────────
            // The same gate the rest of the app uses. Raised the moment "USB drive" is
            // chosen, rather than letting the user walk further into the wizard and find
            // out at the end that there was nothing to write to.
            if (state.usbDetectFailed) {
                AppDialog(
                    onDismissRequest = { viewModel.clearUsbDetectFailed() },
                    title            = { Text(stringResource(R.string.usb_not_connected_title)) },
                    text             = { Text(stringResource(R.string.usb_not_connected_body)) },
                    confirmButton    = {
                        TextButton(onClick = {
                            viewModel.clearUsbDetectFailed()
                            viewModel.detectUsbDrive()
                        }) { Text(stringResource(R.string.usb_try_again)) }
                    },
                    dismissButton    = {
                        TextButton(onClick = { viewModel.clearUsbDetectFailed() }) {
                            Text(stringResource(R.string.common_cancel))
                        }
                    }
                )
            }

            // ── Cancel dialog ─────────────────────────────────────────────────
            if (showCancelDialog) {
                AppDialog(
                    onDismissRequest = { showCancelDialog = false },
                    title            = { Text(stringResource(R.string.create_cancel_dialog_title)) },
                    text             = { Text(stringResource(R.string.create_cancel_dialog_body)) },
                    confirmButton    = {
                        TextButton(onClick = {
                            showCancelDialog = false
                            viewModel.cancelCreation()
                            onBack()
                        }) {
                            Text(stringResource(R.string.create_cancel_dialog_confirm), color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton    = {
                        TextButton(onClick = { showCancelDialog = false }) { Text(stringResource(R.string.create_cancel_dialog_keep)) }
                    }
                )
            }
        }
    }
    } // CompositionLocalProvider
}

private fun isStepValid(state: CreateContainerState, availableSpaceMb: Long = Long.MAX_VALUE): Boolean = when (state.currentStep) {
    1    -> true
    2    -> when (state.location) {
                StorageLocation.APP_STORAGE      -> state.fileName.isNotBlank()
                StorageLocation.INTERNAL_STORAGE -> state.fileName.isNotBlank() && state.safUri.isNotBlank()
                // Two answers, one per page: first that a drive was found at all, then
                // that something on it was chosen to fill.
                // A drive has to have been found; what to fill is asked on step 3.
                StorageLocation.USB_DRIVE        -> state.usbWholeSize > 0L
            }
    3    -> state.usbDataSizeBytes > 0L   // a partition, or the whole drive, is chosen
    4    -> true
    5    -> state.sizeMb > 0L && state.sizeMb <= availableSpaceMb
    6    -> state.password.length >= 4 && state.password == state.confirmPassword &&
            !(state.pim in 1..484 && state.password.length < 20)
    7    -> true
    8    -> true
    9    -> state.entropyPoints >= 500
    11   -> true   // HiddenInfo — always can proceed
    12   -> true   // HiddenAlgorithm
    13   -> state.hiddenSizeMb in 4L..(state.sizeMb - 4L)
    14   -> state.hiddenPassword.length >= 4 &&
            state.hiddenPassword == state.hiddenConfirmPassword &&
            state.hiddenPassword != state.password &&
            !(state.hiddenPim in 1..484 && state.hiddenPassword.length < 20 && state.hiddenKeyfileData.isEmpty())
    15   -> state.hiddenEntropyPoints >= 500
    else -> true
}

