package zip.arcanum.arcanum.containers.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
        val (path, name) = FileUtils.copyUriToCache(context, uri) ?: return@rememberLauncherForActivityResult
        viewModel.addKeyfile(path, name)
    }

    val hiddenKeyfilePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val (path, name) = FileUtils.copyUriToCache(context, uri) ?: return@rememberLauncherForActivityResult
        viewModel.addHiddenKeyfile(path, name)
    }

    BackHandler {
        when {
            state.currentStep in listOf(9, 15) && state.isCreating -> showCancelDialog = true
            state.currentStep == 10 && state.volumeType == VolumeType.STANDARD -> onBack()
            state.currentStep == 10 && state.volumeType == VolumeType.HIDDEN   -> { /* locked: outer already created */ }
            state.currentStep in 11..14 -> viewModel.prevStep()
            state.currentStep in listOf(15, 16) -> { /* locked after hidden creation starts */ }
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
        if (state.currentStep == 9 && !state.isCreating && !state.isCreated) {
            viewModel.startCreation()
        }
        if (state.currentStep == 15 && !state.isCreating && !state.isHiddenCreated) {
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
                .hazeSource(hazeState)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── Top bar ────────────────────────────────────────────────
                val showTopBar = state.currentStep < 9 ||
                    (state.volumeType == VolumeType.HIDDEN && state.currentStep in 10..14)
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
                                    state.currentStep in 10..14 &&
                                    state.volumeType == VolumeType.HIDDEN &&
                                    state.currentStep == 10 -> { /* locked */ }
                                    state.currentStep in 11..14 -> viewModel.prevStep()
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
                            text     = stringResource(R.string.create_step_counter, state.currentStep, state.totalSteps - 1),
                            style    = MaterialTheme.typography.bodySmall,
                            color    = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 16.dp)
                        )
                    }
                }

                // ── Progress bar ──────────────────────────────────────────────
                if (showTopBar) {
                    LinearProgressIndicator(
                        progress   = { (state.currentStep - 1) / (state.totalSteps - 2f) },
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
                                    onClearSaf               = viewModel::clearSafUri
                                )
                        3    -> StepEncryptionAlgorithm(state, viewModel::update)
                        4    -> StepVolumeSize(state, viewModel::update)
                        5    -> StepPassword(
                                    state           = state,
                                    onUpdate        = viewModel::update,
                                    onAddKeyfile    = { keyfilePickerLauncher.launch("*/*") },
                                    onRemoveKeyfile = viewModel::removeKeyfile
                                )
                        6    -> StepFormatMode(state, viewModel::update)
                        7    -> StepFilesystem(state, viewModel::update)
                        8    -> StepEntropy(state, viewModel::addEntropyPoint)
                        9    -> StepCreating(state)
                        10   -> if (state.volumeType == VolumeType.HIDDEN) {
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
                        11   -> StepHiddenAlgorithm(state, viewModel::update)
                        12   -> StepHiddenSize(state, viewModel::update)
                        13   -> StepHiddenPassword(
                                    state           = state,
                                    onUpdate        = viewModel::update,
                                    onAddKeyfile    = { hiddenKeyfilePickerLauncher.launch("*/*") },
                                    onRemoveKeyfile = viewModel::removeHiddenKeyfile
                                )
                        14   -> StepHiddenEntropy(state, viewModel::addHiddenEntropyPoint)
                        15   -> StepCreatingHidden(state)
                        16   -> StepSuccessHidden(
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
                val showNextButton = state.currentStep < 9 ||
                    (state.volumeType == VolumeType.HIDDEN && state.currentStep in 10..14)
                if (showNextButton) {
                    Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                        val buttonLabel = when {
                            state.currentStep == 8 && state.volumeType == VolumeType.HIDDEN   -> stringResource(R.string.create_btn_outer)
                            state.currentStep == 8 && state.volumeType == VolumeType.STANDARD -> stringResource(R.string.create_btn_create)
                            state.currentStep == 14 -> stringResource(R.string.create_btn_hidden)
                            else                    -> stringResource(R.string.create_btn_next)
                        }
                        Button(
                            onClick  = viewModel::nextStep,
                            enabled  = isStepValid(state),
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

private fun isStepValid(state: CreateContainerState): Boolean = when (state.currentStep) {
    1    -> true
    2    -> state.fileName.isNotBlank() && when (state.location) {
                StorageLocation.APP_STORAGE      -> true
                StorageLocation.INTERNAL_STORAGE -> state.safUri.isNotBlank()
            }
    3    -> true
    4    -> state.sizeMb > 0L
    5    -> state.password.length >= 4 && state.password == state.confirmPassword &&
            !(state.pim in 1..484 && state.password.length < 20)
    6    -> true
    7    -> true
    8    -> state.entropyPoints >= 500
    10   -> true   // HiddenInfo — always can proceed
    11   -> true   // HiddenAlgorithm
    12   -> state.hiddenSizeMb in 4L..(state.sizeMb - 4L)
    13   -> state.hiddenPassword.length >= 4 &&
            state.hiddenPassword == state.hiddenConfirmPassword &&
            state.hiddenPassword != state.password &&
            !(state.hiddenPim in 1..484 && state.hiddenPassword.length < 20 && state.hiddenKeyfilePaths.isEmpty())
    14   -> state.hiddenEntropyPoints >= 500
    else -> true
}

