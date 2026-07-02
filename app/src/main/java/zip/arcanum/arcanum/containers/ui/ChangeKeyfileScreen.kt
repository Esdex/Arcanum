package zip.arcanum.arcanum.containers.ui

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Close
import zip.arcanum.core.icons.ArcanumIcons
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlin.math.roundToInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import zip.arcanum.R
import zip.arcanum.core.components.AppDialog
import zip.arcanum.core.utils.FileUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangeKeyfileScreen(
    containerId: String,
    onBack: () -> Unit = {},
    viewModel: ChangeKeyfileViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state   by viewModel.state.collectAsState()

    var prevStep              by remember { mutableIntStateOf(1) }
    var showCancelDialog      by remember { mutableStateOf(false) }
    var showNoKeyfilesDialog  by remember { mutableStateOf(false) }

    LaunchedEffect(containerId) { viewModel.init(containerId) }

    val oldKeyfileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val (path, name) = FileUtils.copyUriToCache(context, uri) ?: return@rememberLauncherForActivityResult
        viewModel.addOldKeyfile(path, name)
    }
    val newKeyfileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val (path, name) = FileUtils.copyUriToCache(context, uri) ?: return@rememberLauncherForActivityResult
        viewModel.addNewKeyfile(path, name)
    }

    BackHandler {
        when {
            state.currentStep == 4 && state.isRunning -> showCancelDialog = true
            state.currentStep > 1 && !state.isRunning -> viewModel.prevStep()
            state.currentStep == 1                    -> onBack()
        }
    }

    LaunchedEffect(state.currentStep) {
        if (state.currentStep == 4 && !state.isRunning && !state.isSuccess && state.error == null) {
            viewModel.startChange()
        }
        prevStep = state.currentStep
    }

    // ── Dialogs ────────────────────────────────────────────────────────────

    if (showCancelDialog) {
        AppDialog(
            onDismissRequest = { showCancelDialog = false },
            title            = { Text(stringResource(R.string.chkeyfile_cancel_title)) },
            text             = { Text(stringResource(R.string.chkeyfile_cancel_body)) },
            confirmButton    = {
                TextButton(onClick = { showCancelDialog = false; onBack() }) {
                    Text(stringResource(R.string.common_exit))
                }
            },
            dismissButton    = {
                TextButton(onClick = { showCancelDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (showNoKeyfilesDialog) {
        AppDialog(
            onDismissRequest = { showNoKeyfilesDialog = false },
            title            = { Text(stringResource(R.string.chkeyfile_no_keyfiles_warning_title)) },
            text             = { Text(stringResource(R.string.chkeyfile_no_keyfiles_warning_body)) },
            confirmButton    = {
                TextButton(onClick = { showNoKeyfilesDialog = false; viewModel.nextStep() }) {
                    Text(stringResource(R.string.common_continue))
                }
            },
            dismissButton    = {
                TextButton(onClick = { showNoKeyfilesDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            Column(modifier = Modifier.fillMaxSize()) {

                val showTopBar = state.currentStep < 4 || state.isSuccess || state.error != null
                if (showTopBar) {
                    Row(
                        modifier          = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            when {
                                state.currentStep > 1 && !state.isRunning -> viewModel.prevStep()
                                state.currentStep == 1                    -> onBack()
                                state.isSuccess || state.error != null    -> onBack()
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text       = stringResource(R.string.chkeyfile_title),
                            style      = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier   = Modifier.weight(1f)
                        )
                        if (state.currentStep < 4) {
                            Text(
                                text     = stringResource(R.string.create_step_counter, state.currentStep, state.totalSteps - 1),
                                style    = MaterialTheme.typography.bodySmall,
                                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 16.dp)
                            )
                        }
                    }
                }

                if (showTopBar && state.currentStep < 4) {
                    LinearProgressIndicator(
                        progress   = { (state.currentStep - 1) / (state.totalSteps - 2f) },
                        modifier   = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(4.dp),
                        strokeCap  = StrokeCap.Round,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                }

                AnimatedContent(
                    targetState = state.currentStep,
                    transitionSpec = {
                        val forward = targetState >= initialState
                        val enter = slideInHorizontally(spring()) { if (forward) it else -it }
                        val exit  = slideOutHorizontally(spring()) { if (forward) -it else it }
                        enter togetherWith exit
                    },
                    label    = "chkeyfile_step",
                    modifier = Modifier.weight(1f).padding(horizontal = 20.dp)
                ) { step ->
                    when (step) {
                        1 -> ChKfStep1(
                            state           = state,
                            onUpdate        = viewModel::update,
                            onAddKeyfile    = { oldKeyfileLauncher.launch("*/*") },
                            onRemoveKeyfile = viewModel::removeOldKeyfile
                        )
                        2 -> ChKfStep2(
                            state            = state,
                            onToggleKeyfiles = viewModel::toggleAddKeyfiles,
                            onAddKeyfile     = { newKeyfileLauncher.launch("*/*") },
                            onRemoveKeyfile  = viewModel::removeNewKeyfile
                        )
                        3 -> ChKfStep3(
                            state             = state,
                            onAddEntropyPoint = viewModel::addEntropyPoint
                        )
                        4 -> ChKfStep4(state = state, onBack = onBack)
                        else -> {}
                    }
                }

                if (state.currentStep < 4) {
                    val canProceed = when (state.currentStep) {
                        1    -> state.password.isNotEmpty()
                        2    -> !state.addKeyfilesEnabled || state.newKeyfilePaths.isNotEmpty()
                        3    -> state.entropyProgress >= 1f
                        else -> false
                    }
                    Button(
                        onClick  = {
                            when (state.currentStep) {
                                2    -> if (!state.addKeyfilesEnabled) showNoKeyfilesDialog = true
                                       else viewModel.nextStep()
                                3    -> viewModel.startChange()
                                else -> viewModel.nextStep()
                            }
                        },
                        enabled  = canProceed,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)
                    ) {
                        Text(
                            if (state.currentStep == 3) stringResource(R.string.chkeyfile_btn_change)
                            else stringResource(R.string.common_next)
                        )
                    }
                }
            }
        }
    }
}

// ─── Step 1: Current Credentials ─────────────────────────────────────────────

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun ChKfStep1(
    state: ChangeKeyfileState,
    onUpdate: (ChangeKeyfileState.() -> ChangeKeyfileState) -> Unit,
    onAddKeyfile: () -> Unit,
    onRemoveKeyfile: (Int) -> Unit
) {
    val context         = LocalContext.current
    val autofill        = LocalAutofill.current
    val autofillManager = remember { context.getSystemService(android.view.autofill.AutofillManager::class.java) }
    val autofillScope   = rememberCoroutineScope()
    val lifecycleOwner  = LocalLifecycleOwner.current
    val focusRequester  = remember { FocusRequester() }
    val latestOnUpdate  = rememberUpdatedState(onUpdate)

    val autofillNode = remember {
        AutofillNode(listOf(AutofillType.Password)) { filled ->
            latestOnUpdate.value { copy(password = filled) }
        }
    }
    val autofillTree = LocalAutofillTree.current
    DisposableEffect(Unit) {
        autofillTree += autofillNode
        onDispose { autofillTree.children.remove(autofillNode.id) }
    }

    var refocusCount by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { autofillManager?.cancel(); refocusCount++ }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(refocusCount) {
        if (refocusCount == 0) return@LaunchedEffect
        delay(200); focusRequester.requestFocus()
    }

    var showPassword    by remember { mutableStateOf(false) }
    var keyfileExpanded by remember { mutableStateOf(state.oldKeyfilePaths.isNotEmpty()) }
    var pimText         by remember { mutableStateOf(if (state.pim > 0) state.pim.toString() else "") }

    StepContent(
        title    = stringResource(R.string.chkeyfile_step1_title),
        subtitle = stringResource(R.string.chkeyfile_step1_subtitle)
    ) {
        OutlinedTextField(
            value         = state.password,
            onValueChange = { onUpdate { copy(password = it) } },
            label         = { Text(stringResource(R.string.chpwd_current_pwd_label)) },
            singleLine    = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon  = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = null
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onGloballyPositioned { autofillNode.boundingBox = it.boundsInRoot() }
                .onFocusChanged { fs ->
                    if (fs.isFocused) {
                        autofillScope.launch {
                            delay(150)
                            autofillManager?.cancel()
                            autofill?.requestAutofillForNode(autofillNode)
                        }
                    } else {
                        autofill?.cancelAutofillForNode(autofillNode)
                    }
                }
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value         = pimText,
            onValueChange = {
                if (it.all { c -> c.isDigit() } && it.length <= 4) {
                    pimText = it
                    onUpdate { copy(pim = it.toIntOrNull() ?: 0) }
                }
            },
            label           = { Text(stringResource(R.string.create_pim_short_label)) },
            placeholder     = { Text(stringResource(R.string.create_pim_placeholder)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine      = true,
            modifier        = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))

        KeyfileSection(
            expanded     = keyfileExpanded,
            displayNames = state.oldKeyfileDisplayNames,
            onToggle     = { keyfileExpanded = !keyfileExpanded },
            onAdd        = onAddKeyfile,
            onRemove     = onRemoveKeyfile
        )
    }
}

// ─── Step 2: New Keyfiles ─────────────────────────────────────────────────────

@Composable
private fun ChKfStep2(
    state: ChangeKeyfileState,
    onToggleKeyfiles: (Boolean) -> Unit,
    onAddKeyfile: () -> Unit,
    onRemoveKeyfile: (Int) -> Unit
) {
    StepContent(
        title    = stringResource(R.string.chkeyfile_step2_title),
        subtitle = stringResource(R.string.chkeyfile_step2_subtitle)
    ) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text  = stringResource(R.string.chkeyfile_add_keyfiles_toggle),
                style = MaterialTheme.typography.bodyLarge
            )
            Switch(
                checked         = state.addKeyfilesEnabled,
                onCheckedChange = onToggleKeyfiles
            )
        }

        AnimatedVisibility(
            visible = state.addKeyfilesEnabled,
            enter   = fadeIn(tween(200)) + expandVertically(tween(250)),
            exit    = fadeOut(tween(150)) + shrinkVertically(tween(200))
        ) {
            Column {
                Spacer(Modifier.height(16.dp))
                Card(
                    colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape    = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.newKeyfileDisplayNames.forEachIndexed { index, name ->
                            Row(
                                modifier          = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(ArcanumIcons.Keyfile, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(8.dp))
                                Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                IconButton(onClick = { onRemoveKeyfile(index) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                        TextButton(onClick = onAddKeyfile, modifier = Modifier.fillMaxWidth()) {
                            Icon(ArcanumIcons.Keyfile, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.create_keyfile_add_item))
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = !state.addKeyfilesEnabled,
            enter   = fadeIn(tween(200)) + expandVertically(tween(250)),
            exit    = fadeOut(tween(150)) + shrinkVertically(tween(200))
        ) {
            Column {
                Spacer(Modifier.height(12.dp))
                Text(
                    text  = stringResource(R.string.chkeyfile_no_keyfiles_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ─── Step 3: Random Pool Enrichment ──────────────────────────────────────────

private data class ChKfParticle(val offset: Offset, val birthMs: Long)

@Composable
private fun ChKfStep3(
    state: ChangeKeyfileState,
    onAddEntropyPoint: (Int, Int) -> Unit
) {
    val accentColor = MaterialTheme.colorScheme.primary
    val done        = state.entropyProgress >= 1f
    val view        = LocalView.current

    val particles  = remember { mutableStateListOf<ChKfParticle>() }
    val uniquePts  = remember { mutableSetOf<Pair<Int, Int>>() }

    LaunchedEffect(Unit) {
        while (true) {
            delay(50)
            val now = System.currentTimeMillis()
            particles.removeAll { now - it.birthMs > 1000L }
        }
    }

    StepContent(
        title    = stringResource(R.string.chkeyfile_step3_title),
        subtitle = stringResource(R.string.chkeyfile_step3_subtitle)
    ) {
        Card(
            colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            shape    = RoundedCornerShape(20.dp),
            border   = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
            modifier = Modifier.fillMaxWidth().height(260.dp)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ ->
                            val pos   = change.position
                            val gridX = (pos.x / 10).roundToInt()
                            val gridY = (pos.y / 10).roundToInt()
                            if (uniquePts.add(gridX to gridY)) {
                                onAddEntropyPoint(gridX, gridY)
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            particles.add(ChKfParticle(pos, System.currentTimeMillis()))
                        }
                    }
            ) {
                val now = System.currentTimeMillis()
                particles.forEach { p ->
                    val age    = (now - p.birthMs) / 1000f
                    val alpha  = (1f - age).coerceIn(0f, 1f)
                    val radius = 6f * (1f - age * 0.5f)
                    drawCircle(color = accentColor.copy(alpha = alpha), radius = radius, center = p.offset)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            progress   = { state.entropyProgress },
            color      = if (done) Color(0xFF16A34A) else accentColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap  = StrokeCap.Round,
            modifier   = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(99.dp))
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text  = if (done) stringResource(R.string.create_entropy_done)
                    else stringResource(R.string.create_entropy_progress, (state.entropyProgress * 100).toInt()),
            style = MaterialTheme.typography.bodySmall,
            color = if (done) Color(0xFF16A34A) else MaterialTheme.colorScheme.onSurfaceVariant
        )

    }
}

// ─── Step 4: Progress / Result ────────────────────────────────────────────────

@Composable
private fun ChKfStep4(state: ChangeKeyfileState, onBack: () -> Unit) {
    when {
        state.isRunning     -> ChKfStep4Loading()
        state.isSuccess     -> ChKfStep4Success(onBack)
        state.error != null -> ChKfStep4Error(state.error, onBack)
        else                -> ChKfStep4Loading()
    }
}

@Composable
private fun ChKfStep4Loading() {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.loading))
    val progress    by animateLottieCompositionAsState(composition, iterations = LottieConstants.IterateForever)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LottieAnimation(composition, { progress }, modifier = Modifier.size(160.dp))
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.chkeyfile_title),
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun ChKfStep4Success(onBack: () -> Unit) {
    val haptic      = LocalHapticFeedback.current
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.success_check))
    val progress    by animateLottieCompositionAsState(composition, iterations = 1)
    LaunchedEffect(Unit) { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            LottieAnimation(composition, { progress }, modifier = Modifier.size(160.dp))
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.chkeyfile_success_title),
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.chkeyfile_success_body),
                style     = MaterialTheme.typography.bodyMedium,
                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        Button(
            onClick  = onBack,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp)
        ) { Text(stringResource(R.string.common_done)) }
    }
}

@Composable
private fun ChKfStep4Error(error: String, onBack: () -> Unit) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.error))
    val progress    by animateLottieCompositionAsState(composition, iterations = 1)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            LottieAnimation(composition, { progress }, modifier = Modifier.size(160.dp))
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.chkeyfile_error_title),
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color      = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(8.dp))
            Text(
                error,
                style     = MaterialTheme.typography.bodySmall,
                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        Button(
            onClick  = onBack,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp)
        ) { Text(stringResource(R.string.common_done)) }
    }
}
