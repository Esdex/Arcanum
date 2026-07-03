package zip.arcanum.arcanum.containers.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import zip.arcanum.R
import zip.arcanum.core.utils.DotVisualTransformation
import zip.arcanum.core.utils.FileUtils
import java.text.DecimalFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpandVolumeScreen(
    containerId: String,
    onBack: () -> Unit,
    viewModel: ExpandVolumeViewModel = hiltViewModel()
) {
    val state            by viewModel.state.collectAsState()
    val context           = LocalContext.current
    val currentSizeBytes  = viewModel.currentFileSizeBytes
    var step             by remember { mutableIntStateOf(1) }
    var sizeError        by remember { mutableStateOf<String?>(null) }

    val newSizeMb = remember(state.newSizeInput, state.sizeUnit) {
        val v = state.newSizeInput.toLongOrNull() ?: 0L
        when (state.sizeUnit) {
            SizeUnit.MB -> v
            SizeUnit.GB -> v * 1024L
        }
    }
    val additionalMb = (newSizeMb - currentSizeBytes / (1024L * 1024L)).coerceAtLeast(0L)
    val notEnoughSpace = state.availableSpaceMb != Long.MAX_VALUE && newSizeMb > 0L && additionalMb > state.availableSpaceMb

    LaunchedEffect(containerId) { viewModel.init(containerId) }
    LaunchedEffect(state.isRunning) { if (state.isRunning) step = 3 }
    LaunchedEffect(state.isSuccess, state.error) { if (state.isSuccess || state.error != null) step = 3 }

    BackHandler(enabled = step == 3 && state.isRunning) {}
    BackHandler(enabled = step == 2) { step = 1 }

    val keyfileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val (path, name) = FileUtils.copyUriToCache(context, it)
                ?: return@rememberLauncherForActivityResult
            viewModel.addKeyfile(path, name)
        }
    }

    val showTopBar = step < 3 || state.isSuccess || state.error != null

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            Column(modifier = Modifier.fillMaxSize()) {

                // ── Top bar ────────────────────────────────────────────────────
                if (showTopBar) {
                    Row(
                        modifier          = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { if (step == 2) step = 1 else onBack() }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text       = stringResource(R.string.expand_volume_title),
                            style      = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier   = Modifier.weight(1f)
                        )
                        if (step < 3) {
                            Text(
                                text     = stringResource(R.string.create_step_counter, step, 2),
                                style    = MaterialTheme.typography.bodySmall,
                                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 16.dp)
                            )
                        }
                    }
                }

                if (showTopBar && step < 3) {
                    LinearProgressIndicator(
                        progress   = { (step - 1) / 2f },
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
                    targetState    = step,
                    transitionSpec = {
                        if (targetState == 3 || initialState == 3) {
                            fadeIn(tween(300)) togetherWith fadeOut(tween(300))
                        } else {
                            val fwd = targetState > initialState
                            (slideInHorizontally(tween(300)) { if (fwd) it else -it } + fadeIn(tween(150))) togetherWith
                            (slideOutHorizontally(tween(300)) { if (fwd) -it else it } + fadeOut(tween(150)))
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 20.dp),
                    label    = "expand_step"
                ) { s ->
                    when (s) {
                        1 -> ExpandStep1(
                            state        = state,
                            viewModel    = viewModel,
                            onAddKeyfile = { keyfileLauncher.launch("*/*") }
                        )
                        2 -> ExpandStep2(
                            state             = state,
                            viewModel         = viewModel,
                            currentSizeBytes  = currentSizeBytes,
                            sizeError         = sizeError,
                            onSizeErrorChange = { sizeError = it },
                            availableSpaceMb  = state.availableSpaceMb,
                            notEnoughSpace    = notEnoughSpace
                        )
                        else -> ExpandStep3(
                            state   = state,
                            onDone  = onBack,
                            onRetry = {
                                viewModel.update {
                                    copy(error = null, isSuccess = false, progress = 0f, speedMbps = 0f, isRunning = false)
                                }
                                step = 1
                            }
                        )
                    }
                }

                // ── Bottom button ──────────────────────────────────────────────
                if (step < 3) {
                    val canProceed = when (step) {
                        1    -> state.password.isNotEmpty()
                        else -> state.newSizeInput.isNotEmpty() && state.isReady && !notEnoughSpace
                    }
                    Button(
                        onClick  = {
                            when (step) {
                                1 -> { sizeError = null; step = 2 }
                                2 -> {
                                    val inputLong = state.newSizeInput.toLongOrNull() ?: 0L
                                    val newSizeBytes = when (state.sizeUnit) {
                                        SizeUnit.MB -> inputLong * 1024L * 1024L
                                        SizeUnit.GB -> inputLong * 1024L * 1024L * 1024L
                                    }
                                    when {
                                        inputLong <= 0L || newSizeBytes <= currentSizeBytes + 65536L ->
                                            sizeError = "expand_too_small"
                                        newSizeBytes % 512 != 0L ->
                                            sizeError = "expand_not_aligned"
                                        else -> { step = 3; viewModel.startExpand() }
                                    }
                                }
                            }
                        },
                        enabled  = canProceed,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp)
                    ) {
                        Text(
                            if (step == 1) stringResource(R.string.common_next)
                            else stringResource(R.string.expand_volume_button)
                        )
                    }
                }
            }
        }
    }
}

// ─── Step 1: Credentials ─────────────────────────────────────────────────────

@Composable
private fun ExpandStep1(
    state: ExpandVolumeState,
    viewModel: ExpandVolumeViewModel,
    onAddKeyfile: () -> Unit
) {
    var showPassword by remember { mutableStateOf(false) }
    var showPim      by remember { mutableStateOf(false) }
    var pimText      by remember { mutableStateOf(if (state.pim > 0) state.pim.toString() else "") }

    StepContent(title = stringResource(R.string.expand_step1_heading)) {
        OutlinedTextField(
            value         = state.password,
            onValueChange = { viewModel.update { copy(password = it) } },
            label         = { Text(stringResource(R.string.common_password)) },
            singleLine    = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon  = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = null)
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value         = pimText,
            onValueChange = {
                if (it.all { c -> c.isDigit() } && it.length <= 7) {
                    val v = it.toLongOrNull() ?: 0L
                    if (it.isEmpty() || v in 1L..2_147_468L) {
                        pimText = it
                        viewModel.update { copy(pim = v.toInt()) }
                    }
                }
            },
            label                = { Text(stringResource(R.string.vault_mount_pim_label)) },
            placeholder          = { Text(stringResource(R.string.vault_mount_pim_placeholder)) },
            visualTransformation = if (showPim) VisualTransformation.None else DotVisualTransformation(),
            keyboardOptions      = KeyboardOptions(keyboardType = KeyboardType.Number),
            trailingIcon         = {
                IconButton(onClick = { showPim = !showPim }) {
                    Icon(if (showPim) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = null)
                }
            },
            singleLine = true,
            modifier   = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))

        KeyfileSectionCompact(
            displayNames = state.keyfileDisplayNames,
            onAdd        = onAddKeyfile,
            onRemove     = viewModel::removeKeyfile
        )
    }
}

// ─── Step 2: New Size ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpandStep2(
    state: ExpandVolumeState,
    viewModel: ExpandVolumeViewModel,
    currentSizeBytes: Long,
    sizeError: String?,
    onSizeErrorChange: (String?) -> Unit,
    availableSpaceMb: Long = Long.MAX_VALUE,
    notEnoughSpace: Boolean = false
) {
    StepContent(title = stringResource(R.string.expand_new_size_label)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (currentSizeBytes > 0L || availableSpaceMb != Long.MAX_VALUE) {
                Card(
                    colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    shape    = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier          = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (currentSizeBytes > 0L) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.expand_current_size),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    currentSizeBytes.fmtFileSize(),
                                    style      = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        if (currentSizeBytes > 0L && availableSpaceMb != Long.MAX_VALUE) {
                            androidx.compose.material3.VerticalDivider(
                                modifier = Modifier.height(40.dp).padding(horizontal = 16.dp)
                            )
                        }
                        if (availableSpaceMb != Long.MAX_VALUE) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.expand_available_space),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    (availableSpaceMb * 1024L * 1024L).fmtFileSize(),
                                    style      = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color      = if (notEnoughSpace) MaterialTheme.colorScheme.error
                                                 else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment     = Alignment.CenterVertically,
                modifier              = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value         = state.newSizeInput,
                    onValueChange = { v ->
                        onSizeErrorChange(null)
                        viewModel.update { copy(newSizeInput = v.filter { it.isDigit() }.take(7)) }
                    },
                    label           = { Text(stringResource(R.string.expand_size_hint)) },
                    singleLine      = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError         = sizeError != null,
                    modifier        = Modifier.weight(1f)
                )
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(
                        selected = state.sizeUnit == SizeUnit.MB,
                        onClick  = { onSizeErrorChange(null); viewModel.update { copy(sizeUnit = SizeUnit.MB) } },
                        shape    = SegmentedButtonDefaults.itemShape(0, 2)
                    ) { Text("MB") }
                    SegmentedButton(
                        selected = state.sizeUnit == SizeUnit.GB,
                        onClick  = { onSizeErrorChange(null); viewModel.update { copy(sizeUnit = SizeUnit.GB) } },
                        shape    = SegmentedButtonDefaults.itemShape(1, 2)
                    ) { Text("GB") }
                }
            }

            if (sizeError != null) {
                Text(
                    text  = when (sizeError) {
                        "expand_too_small"   -> stringResource(R.string.expand_error_too_small)
                        "expand_not_aligned" -> stringResource(R.string.expand_error_not_aligned)
                        else                 -> stringResource(R.string.expand_error_generic, sizeError)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (notEnoughSpace) {
                Text(
                    stringResource(R.string.create_size_not_enough_space),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Card(
                colors   = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                ),
                shape    = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier              = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment     = Alignment.Top
                ) {
                    Icon(
                        Icons.Outlined.Lock, contentDescription = null,
                        tint     = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(18.dp).padding(top = 2.dp)
                    )
                    Text(
                        stringResource(R.string.expand_fill_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}

// ─── Step 3: Progress / Result ────────────────────────────────────────────────

@Composable
private fun ExpandStep3(
    state: ExpandVolumeState,
    onDone: () -> Unit,
    onRetry: () -> Unit
) {
    val context   = LocalContext.current
    val lottieRes = when {
        state.isSuccess     -> R.raw.success_check
        state.error != null -> R.raw.error
        else                -> R.raw.loading
    }
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(lottieRes))
    val progress    by animateLottieCompositionAsState(
        composition = composition,
        iterations  = if (state.isRunning) LottieConstants.IterateForever else 1,
        isPlaying   = true
    )

    val errorMsg = state.error?.let { err ->
        when (err) {
            "WRONG_PASSWORD"        -> context.getString(R.string.expand_error_wrong_password)
            "UNSUPPORTED_ALGORITHM" -> context.getString(R.string.expand_error_has_hidden)
            "IO_ERROR"              -> context.getString(R.string.expand_error_io)
            "expand_too_small"      -> context.getString(R.string.expand_error_too_small)
            "expand_not_aligned"    -> context.getString(R.string.expand_error_not_aligned)
            else                    -> context.getString(R.string.expand_error_generic, err)
        }
    }

    Column(
        modifier            = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        LottieAnimation(
            composition = composition,
            progress    = { progress },
            modifier    = Modifier.size(180.dp)
        )
        Spacer(Modifier.height(16.dp))

        when {
            state.isRunning -> {
                Text(
                    text       = stringResource(R.string.expand_progress_title),
                    style      = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign  = TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${(state.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.speedMbps > 0f) {
                        Text(
                            context.getString(R.string.expand_speed, DecimalFormat("#.#").format(state.speedMbps)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            state.isSuccess -> {
                Text(
                    text       = stringResource(R.string.expand_success_title),
                    style      = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign  = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text      = stringResource(R.string.expand_success_body),
                    style     = MaterialTheme.typography.bodyMedium,
                    color     = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.common_done))
                }
            }

            state.error != null -> {
                Text(
                    text       = stringResource(R.string.expand_error_title),
                    style      = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign  = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text      = errorMsg ?: "",
                    style     = MaterialTheme.typography.bodyMedium,
                    color     = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.expand_try_again))
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.common_done))
                }
            }
        }
    }
}

private fun Long.fmtFileSize(): String {
    val gb  = this / (1024.0 * 1024.0 * 1024.0)
    val mb  = this / (1024.0 * 1024.0)
    val fmt = DecimalFormat("#.##")
    return when {
        gb >= 1.0 -> "${fmt.format(gb)} GB"
        mb >= 1.0 -> "${fmt.format(mb)} MB"
        else      -> "${fmt.format(this / 1024.0)} KB"
    }
}
