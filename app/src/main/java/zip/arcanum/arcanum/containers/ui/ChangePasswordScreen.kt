package zip.arcanum.arcanum.containers.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
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
fun ChangePasswordScreen(
    containerId: String,
    onBack: () -> Unit = {},
    viewModel: ChangePasswordViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state   by viewModel.state.collectAsState()

    var prevStep          by remember { mutableIntStateOf(1) }
    var showCancelDialog  by remember { mutableStateOf(false) }

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

    if (showCancelDialog) {
        AppDialog(
            onDismissRequest = { showCancelDialog = false },
            title            = { Text(stringResource(R.string.chpwd_cancel_title)) },
            text             = { Text(stringResource(R.string.chpwd_cancel_body)) },
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

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                // ── Top bar ────────────────────────────────────────────────────
                val showTopBar = state.currentStep < 4 || state.isSuccess || state.error != null
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
                                    state.currentStep > 1 && !state.isRunning -> viewModel.prevStep()
                                    state.currentStep == 1                    -> onBack()
                                    state.isSuccess || state.error != null    -> onBack()
                                }
                            }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text       = stringResource(R.string.chpwd_title),
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
                        modifier   = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .height(4.dp),
                        strokeCap  = StrokeCap.Round,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                }

                // ── Step content ───────────────────────────────────────────────
                AnimatedContent(
                    targetState = state.currentStep,
                    transitionSpec = {
                        val forward = targetState >= initialState
                        val enter = slideInHorizontally(spring()) { if (forward) it else -it }
                        val exit  = slideOutHorizontally(spring()) { if (forward) -it else it }
                        enter togetherWith exit
                    },
                    label    = "chpwd_step",
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 20.dp)
                ) { step ->
                    when (step) {
                        1 -> ChPwdStep1(
                            state           = state,
                            onUpdate        = viewModel::update,
                            onAddKeyfile    = { oldKeyfileLauncher.launch("*/*") },
                            onRemoveKeyfile = viewModel::removeOldKeyfile
                        )
                        2 -> ChPwdStep2(
                            state           = state,
                            onUpdate        = viewModel::update,
                            onAddKeyfile    = { newKeyfileLauncher.launch("*/*") },
                            onRemoveKeyfile = viewModel::removeNewKeyfile
                        )
                        3 -> ChPwdStep3(state = state, onUpdate = viewModel::update)
                        4 -> ChPwdStep4(state = state, onBack = onBack)
                        else -> {}
                    }
                }

                // ── Bottom button ─────────────────────────────────────────────
                if (state.currentStep < 4) {
                    val canProceed = when (state.currentStep) {
                        1    -> state.oldPassword.isNotEmpty()
                        2    -> state.newPassword.isNotEmpty() &&
                                state.newPassword == state.newConfirmPassword
                        3    -> true
                        else -> false
                    }
                    Button(
                        onClick  = {
                            if (state.currentStep == 3) viewModel.startChange()
                            else viewModel.nextStep()
                        },
                        enabled  = canProceed,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp)
                    ) {
                        Text(
                            if (state.currentStep == 3) stringResource(R.string.chpwd_btn_change)
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
private fun ChPwdStep1(
    state: ChangePasswordState,
    onUpdate: (ChangePasswordState.() -> ChangePasswordState) -> Unit,
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
            latestOnUpdate.value { copy(oldPassword = filled) }
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
    var pimText         by remember { mutableStateOf(if (state.oldPim > 0) state.oldPim.toString() else "") }

    StepContent(
        title    = stringResource(R.string.chpwd_step1_title),
        subtitle = stringResource(R.string.chpwd_step1_subtitle)
    ) {
        OutlinedTextField(
            value         = state.oldPassword,
            onValueChange = { onUpdate { copy(oldPassword = it) } },
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
                    onUpdate { copy(oldPim = it.toIntOrNull() ?: 0) }
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

// ─── Step 2: New Credentials ──────────────────────────────────────────────────

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun ChPwdStep2(
    state: ChangePasswordState,
    onUpdate: (ChangePasswordState.() -> ChangePasswordState) -> Unit,
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

    // NewPassword type — password managers know to generate/suggest a new credential.
    // Fills both fields so the user never has to manually match them.
    val autofillNode = remember {
        AutofillNode(listOf(AutofillType.NewPassword)) { filled ->
            latestOnUpdate.value { copy(newPassword = filled, newConfirmPassword = filled) }
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
    var showConfirm     by remember { mutableStateOf(false) }
    var keyfileExpanded by remember { mutableStateOf(state.newKeyfilePaths.isNotEmpty()) }
    var pimText         by remember { mutableStateOf(if (state.newPim > 0) state.newPim.toString() else "") }

    StepContent(
        title    = stringResource(R.string.chpwd_step2_title),
        subtitle = stringResource(R.string.chpwd_step2_subtitle)
    ) {
        OutlinedTextField(
            value         = state.newPassword,
            onValueChange = { onUpdate { copy(newPassword = it) } },
            label         = { Text(stringResource(R.string.chpwd_new_pwd_label)) },
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
            value         = state.newConfirmPassword,
            onValueChange = { onUpdate { copy(newConfirmPassword = it) } },
            label         = { Text(stringResource(R.string.chpwd_new_pwd_confirm_label)) },
            singleLine    = true,
            visualTransformation = if (showConfirm) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError        = state.newConfirmPassword.isNotEmpty() && state.newPassword != state.newConfirmPassword,
            supportingText = if (state.newConfirmPassword.isNotEmpty() && state.newPassword != state.newConfirmPassword) {
                { Text(stringResource(R.string.create_pwd_mismatch)) }
            } else null,
            trailingIcon  = {
                IconButton(onClick = { showConfirm = !showConfirm }) {
                    Icon(
                        if (showConfirm) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = null
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value         = pimText,
            onValueChange = {
                if (it.all { c -> c.isDigit() } && it.length <= 4) {
                    pimText = it
                    onUpdate { copy(newPim = it.toIntOrNull() ?: 0) }
                }
            },
            label           = { Text(stringResource(R.string.create_pim_short_label)) },
            placeholder     = { Text(stringResource(R.string.create_pim_placeholder)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine      = true,
            modifier        = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))

        KeyfileSection(
            expanded     = keyfileExpanded,
            displayNames = state.newKeyfileDisplayNames,
            onToggle     = { keyfileExpanded = !keyfileExpanded },
            onAdd        = onAddKeyfile,
            onRemove     = onRemoveKeyfile
        )
        Spacer(Modifier.height(16.dp))

        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        Text(
            stringResource(R.string.chpwd_pkcs5_label),
            style      = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier              = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HashAlgorithm.entries.forEach { hash ->
                FilterChip(
                    selected = state.newHashAlgorithm == hash,
                    onClick  = { onUpdate { copy(newHashAlgorithm = hash) } },
                    label    = { Text(hash.displayName) }
                )
            }
        }
    }
}

// ─── Step 3: Wipe Mode ────────────────────────────────────────────────────────

@Composable
private fun ChPwdStep3(
    state: ChangePasswordState,
    onUpdate: (ChangePasswordState.() -> ChangePasswordState) -> Unit
) {
    data class WipeModeData(
        val mode: WipeMode,
        val nameRes: Int,
        val descRes: Int,
        val speed: AlgorithmSpeed
    )

    val modes = listOf(
        WipeModeData(WipeMode.PASS_1,   R.string.chpwd_wipe_1pass,   R.string.chpwd_wipe_1pass_desc,   AlgorithmSpeed.FAST),
        WipeModeData(WipeMode.PASS_3,   R.string.chpwd_wipe_3pass,   R.string.chpwd_wipe_3pass_desc,   AlgorithmSpeed.MEDIUM),
        WipeModeData(WipeMode.PASS_7,   R.string.chpwd_wipe_7pass,   R.string.chpwd_wipe_7pass_desc,   AlgorithmSpeed.SLOW),
        WipeModeData(WipeMode.PASS_35,  R.string.chpwd_wipe_35pass,  R.string.chpwd_wipe_35pass_desc,  AlgorithmSpeed.EXTREMELY_SLOW),
        WipeModeData(WipeMode.PASS_256, R.string.chpwd_wipe_256pass, R.string.chpwd_wipe_256pass_desc, AlgorithmSpeed.PARANOIA),
    )

    StepContent(
        title    = stringResource(R.string.chpwd_step3_title),
        subtitle = stringResource(R.string.chpwd_step3_subtitle)
    ) {
        modes.forEach { data ->
            val name     = stringResource(data.nameRes)
            val desc     = stringResource(data.descRes)
            val selected = state.wipeMode == data.mode
            val speedLabel = when (data.speed) {
                AlgorithmSpeed.FAST           -> stringResource(R.string.create_size_speed_fast)
                AlgorithmSpeed.MEDIUM         -> stringResource(R.string.create_size_speed_medium)
                AlgorithmSpeed.SLOW           -> stringResource(R.string.create_size_speed_slow)
                AlgorithmSpeed.EXTREMELY_SLOW -> stringResource(R.string.create_size_speed_extremely_slow)
                AlgorithmSpeed.PARANOIA       -> stringResource(R.string.create_size_speed_paranoia)
            }
            val speedColor = when (data.speed) {
                AlgorithmSpeed.FAST           -> MaterialTheme.colorScheme.tertiary
                AlgorithmSpeed.MEDIUM         -> MaterialTheme.colorScheme.secondary
                AlgorithmSpeed.SLOW,
                AlgorithmSpeed.EXTREMELY_SLOW,
                AlgorithmSpeed.PARANOIA       -> MaterialTheme.colorScheme.error
            }
            val borderColor = if (selected) MaterialTheme.colorScheme.primary
                              else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
            Card(
                onClick  = { onUpdate { copy(wipeMode = data.mode) } },
                border   = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
                colors   = CardDefaults.cardColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                     else MaterialTheme.colorScheme.surface
                ),
                shape    = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier          = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(speedLabel, style = MaterialTheme.typography.labelSmall, color = speedColor, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ─── Step 4: Progress / Result ────────────────────────────────────────────────

@Composable
private fun ChPwdStep4(state: ChangePasswordState, onBack: () -> Unit) {
    when {
        state.isRunning  -> ChPwdStep4Loading()
        state.isSuccess  -> ChPwdStep4Success(onBack)
        state.error != null -> ChPwdStep4Error(state.error, onBack)
    }
}

@Composable
private fun ChPwdStep4Loading() {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.loading))
    Box(
        modifier         = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            LottieAnimation(
                composition = composition,
                iterations  = LottieConstants.IterateForever,
                modifier    = Modifier.size(160.dp)
            )
            Text(
                stringResource(R.string.chpwd_step4_title),
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign  = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ChPwdStep4Success(onBack: () -> Unit) {
    val haptic      = LocalHapticFeedback.current
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.success_check))
    val progress    by animateLottieCompositionAsState(composition = composition, iterations = 1)

    LaunchedEffect(Unit) { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }

    Column(
        modifier            = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LottieAnimation(
                    composition = composition,
                    progress    = { progress },
                    modifier    = Modifier.size(180.dp)
                )
                Text(
                    stringResource(R.string.chpwd_step4_success_title),
                    style      = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign  = TextAlign.Center
                )
                Text(
                    stringResource(R.string.chpwd_step4_success_body),
                    style     = MaterialTheme.typography.bodyMedium,
                    color     = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        Button(
            onClick  = onBack,
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
        ) {
            Text(stringResource(R.string.common_done), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ChPwdStep4Error(error: String, onBack: () -> Unit) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.error))
    val progress    by animateLottieCompositionAsState(composition = composition, iterations = 1)

    val errorMsg = if (error == "WRONG_PASSWORD")
        stringResource(R.string.chpwd_step4_error_wrong_password)
    else
        stringResource(R.string.chpwd_step4_error_generic, error)

    Column(
        modifier            = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LottieAnimation(
                    composition = composition,
                    progress    = { progress },
                    modifier    = Modifier.size(180.dp)
                )
                Text(
                    errorMsg,
                    style     = MaterialTheme.typography.bodyMedium,
                    color     = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
        }
        Button(
            onClick  = onBack,
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
        ) {
            Text(stringResource(R.string.common_done), fontWeight = FontWeight.SemiBold)
        }
    }
}

// ─── Shared: Keyfile section ──────────────────────────────────────────────────

@Composable
private fun KeyfileSection(
    expanded: Boolean,
    displayNames: List<String>,
    onToggle: () -> Unit,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit
) {
    TextButton(
        onClick  = onToggle,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Outlined.AttachFile, contentDescription = null)
        Spacer(Modifier.size(6.dp))
        Text(
            if (displayNames.isNotEmpty()) "${displayNames.size} keyfile(s) selected"
            else if (expanded) stringResource(R.string.create_keyfile_hide)
            else stringResource(R.string.create_keyfile_add)
        )
    }
    if (expanded) {
        Card(
            colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            shape    = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                displayNames.forEachIndexed { index, name ->
                    Row(
                        modifier          = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.Key,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint     = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            name,
                            style    = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { onRemove(index) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                TextButton(
                    onClick  = onAdd,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.create_keyfile_add_item))
                }
            }
        }
    }
}

