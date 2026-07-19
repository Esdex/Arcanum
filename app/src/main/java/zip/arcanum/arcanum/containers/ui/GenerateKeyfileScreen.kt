package zip.arcanum.arcanum.containers.ui

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Remove
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import kotlinx.coroutines.delay
import zip.arcanum.R
import zip.arcanum.core.utils.FileUtils
import zip.arcanum.crypto.VeraCryptEngine
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerateKeyfileScreen(
    onBack: () -> Unit = {},
    viewModel: GenerateKeyfileViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    // The destination is a directory, not a file: VeraCrypt's generator asks for
    // one too, and a tree keeps a multi-file run to a single picker round-trip.
    val destinationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        viewModel.generate(uri)
    }

    BackHandler {
        when {
            state.isRunning                          -> { /* locked while writing */ }
            state.currentStep > 1 && !state.isSuccess && state.error == null -> viewModel.prevStep()
            else                                     -> onBack()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            Column(modifier = Modifier.fillMaxSize()) {

                val showTopBar = state.currentStep < 3 || state.isSuccess || state.error != null
                if (showTopBar) {
                    Row(
                        modifier          = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            when {
                                state.currentStep > 1 && !state.isSuccess && state.error == null -> viewModel.prevStep()
                                else -> onBack()
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text       = stringResource(R.string.genkeyfile_title),
                            style      = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier   = Modifier.weight(1f)
                        )
                        if (state.currentStep < 3) {
                            Text(
                                text     = stringResource(R.string.create_step_counter, state.currentStep, state.totalSteps - 1),
                                style    = MaterialTheme.typography.bodySmall,
                                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 16.dp)
                            )
                        }
                    }
                }

                if (showTopBar && state.currentStep < 3) {
                    LinearProgressIndicator(
                        progress   = { (state.currentStep - 1) / (state.totalSteps - 2f) },
                        modifier   = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(4.dp),
                        strokeCap  = StrokeCap.Round,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                }

                AnimatedContent(
                    targetState    = state.currentStep,
                    transitionSpec = {
                        val forward = targetState >= initialState
                        val enter = slideInHorizontally(spring()) { if (forward) it else -it }
                        val exit  = slideOutHorizontally(spring()) { if (forward) -it else it }
                        enter togetherWith exit
                    },
                    label    = "genkeyfile_step",
                    modifier = Modifier.weight(1f).padding(horizontal = 20.dp)
                ) { step ->
                    when (step) {
                        1 -> GenKfStep1(
                            state       = state,
                            onUpdate    = viewModel::update,
                            onSetCount  = viewModel::setCount,
                            onSetSize   = viewModel::setSize
                        )
                        2 -> GenKfStep2(state = state, onAddEntropyPoint = viewModel::addEntropyPoint)
                        3 -> GenKfStep3(state = state, onBack = onBack)
                        else -> {}
                    }
                }

                if (state.currentStep < 3) {
                    val canProceed = when (state.currentStep) {
                        1    -> state.baseNameValid && state.sizeValid
                        2    -> state.entropyProgress >= 1f
                        else -> false
                    }
                    Button(
                        onClick  = {
                            if (state.currentStep == 2) destinationLauncher.launch(null)
                            else viewModel.nextStep()
                        },
                        enabled  = canProceed,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)
                    ) {
                        Text(
                            if (state.currentStep == 2) stringResource(R.string.genkeyfile_btn_choose_folder)
                            else stringResource(R.string.common_next)
                        )
                    }
                }
            }
        }
    }
}

// ─── Step 1: Parameters ──────────────────────────────────────────────────────

@Composable
private fun GenKfStep1(
    state: GenerateKeyfileState,
    onUpdate: (GenerateKeyfileState.() -> GenerateKeyfileState) -> Unit,
    onSetCount: (Int) -> Unit,
    onSetSize: (Int) -> Unit
) {
    var sizeText by remember { mutableStateOf(state.sizeBytes.toString()) }

    StepContent(
        title    = stringResource(R.string.genkeyfile_step1_title),
        subtitle = stringResource(R.string.genkeyfile_step1_subtitle)
    ) {
        OutlinedTextField(
            value         = state.baseName,
            onValueChange = { text -> onUpdate { copy(baseName = text) } },
            label         = { Text(stringResource(R.string.genkeyfile_name_label)) },
            singleLine    = true,
            isError       = state.baseName.isNotEmpty() && !state.baseNameValid,
            supportingText = {
                if (state.baseName.isNotEmpty() && !state.baseNameValid) {
                    Text(stringResource(R.string.genkeyfile_name_invalid))
                }
            },
            modifier      = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))

        // Count stepper
        Row(
            verticalAlignment    = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier             = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.genkeyfile_count_label),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    stringResource(R.string.genkeyfile_count_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = { onSetCount(state.count - 1) },
                enabled = state.count > 1
            ) { Icon(Icons.Outlined.Remove, contentDescription = stringResource(R.string.genkeyfile_cd_fewer)) }
            Text(
                text  = state.count.toString(),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.width(32.dp),
                textAlign = TextAlign.Center
            )
            IconButton(onClick = { onSetCount(state.count + 1) }) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.genkeyfile_cd_more))
            }
        }

        Spacer(Modifier.height(8.dp))

        // Random size toggle
        Row(
            verticalAlignment    = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier             = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.genkeyfile_random_size_label),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    stringResource(R.string.genkeyfile_random_size_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked         = state.randomSize,
                onCheckedChange = { checked -> onUpdate { copy(randomSize = checked) } }
            )
        }

        AnimatedVisibility(
            visible = !state.randomSize,
            enter   = fadeIn(),
            exit    = fadeOut()
        ) {
            Column {
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value         = sizeText,
                    onValueChange = { text ->
                        sizeText = text.filter { it.isDigit() }.take(7)
                        // 0 for empty/unparseable so sizeValid rejects it instead
                        // of silently keeping the previous size.
                        onSetSize(sizeText.toIntOrNull() ?: 0)
                    },
                    label           = { Text(stringResource(R.string.genkeyfile_size_label)) },
                    singleLine      = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError         = !state.sizeValid,
                    supportingText  = {
                        Text(
                            stringResource(
                                R.string.genkeyfile_size_hint,
                                VeraCryptEngine.KEYFILE_MIN_SIZE,
                                FileUtils.getHumanReadableSize(VeraCryptEngine.KEYFILE_MAX_SIZE.toLong())
                            )
                        )
                    },
                    modifier        = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            shape  = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(modifier = Modifier.padding(16.dp)) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint     = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.genkeyfile_backup_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ─── Step 2: Entropy ─────────────────────────────────────────────────────────

private data class GenKfParticle(val offset: Offset, val birthMs: Long)

@Composable
private fun GenKfStep2(
    state: GenerateKeyfileState,
    onAddEntropyPoint: (Int, Int) -> Unit
) {
    val accentColor = MaterialTheme.colorScheme.primary
    val done        = state.entropyProgress >= 1f
    val view        = LocalView.current

    val particles = remember { mutableStateListOf<GenKfParticle>() }
    val uniquePts = remember { mutableSetOf<Pair<Int, Int>>() }

    LaunchedEffect(Unit) {
        while (true) {
            delay(50)
            val now = System.currentTimeMillis()
            particles.removeAll { now - it.birthMs > 1000L }
        }
    }

    StepContent(
        title    = stringResource(R.string.create_entropy_title),
        subtitle = stringResource(R.string.genkeyfile_entropy_subtitle)
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
                            particles.add(GenKfParticle(pos, System.currentTimeMillis()))
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

// ─── Step 3: Result ──────────────────────────────────────────────────────────

@Composable
private fun GenKfStep3(state: GenerateKeyfileState, onBack: () -> Unit) {
    when {
        state.isRunning     -> GenKfLoading()
        state.isSuccess     -> GenKfSuccess(state.generatedNames, onBack)
        state.error != null -> GenKfError(state.error, onBack)
        else                -> GenKfLoading()
    }
}

@Composable
private fun GenKfLoading() {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.loading))
    val progress    by animateLottieCompositionAsState(composition, iterations = LottieConstants.IterateForever)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LottieAnimation(composition, { progress }, modifier = Modifier.size(160.dp))
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.genkeyfile_running),
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun GenKfSuccess(names: List<String>, onBack: () -> Unit) {
    val haptic      = LocalHapticFeedback.current
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.success_check))
    val progress    by animateLottieCompositionAsState(composition, iterations = 1)
    LaunchedEffect(Unit) { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            LottieAnimation(composition, { progress }, modifier = Modifier.size(160.dp))
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.genkeyfile_success_title),
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                names.joinToString("\n"),
                style     = MaterialTheme.typography.bodySmall,
                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.genkeyfile_backup_warning),
                style     = MaterialTheme.typography.bodySmall,
                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier  = Modifier.padding(horizontal = 24.dp)
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
private fun GenKfError(error: String, onBack: () -> Unit) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.error))
    val progress    by animateLottieCompositionAsState(composition, iterations = 1)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            LottieAnimation(composition, { progress }, modifier = Modifier.size(160.dp))
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.genkeyfile_error_title),
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
