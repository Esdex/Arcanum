package zip.arcanum.arcanum.containers.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import zip.arcanum.core.notifications.InAppNotification
import zip.arcanum.core.notifications.LocalNotifications
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Switch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.rememberModalBottomSheetState
import zip.arcanum.core.components.AppSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import zip.arcanum.core.utils.DotVisualTransformation
import zip.arcanum.R
import zip.arcanum.core.icons.ArcanumIcons
import zip.arcanum.core.components.AppDialog
import zip.arcanum.core.database.entities.ContainerEntity
import zip.arcanum.core.utils.FileUtils
import zip.arcanum.crypto.VeraCryptEngine
import javax.crypto.Cipher
import kotlin.math.roundToInt
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class KeyfileEntry(val content: ByteArray, val displayName: String, val uriString: String) {
    fun zero() = content.fill(0)
}

private sealed interface BioUiMode {
    data object Indicator : BioUiMode
    data object Cancelled : BioUiMode
    data object Form      : BioUiMode
}

private data class EncryptPending(
    val password: String,
    val pim: Int,
    val hash: Int,
    val protectHidden: String?,
    val protectHiddenPim: Int,
    val protectHiddenKeyfileData: List<ByteArray>,
    val protectHiddenHash: Int
)

@Composable
fun MountScreen(
    containerId: String,
    viewModel: VaultViewModel,
    onBack: () -> Unit,
    onMountSuccess: (id: String) -> Unit
) {
    val containers by viewModel.containers.collectAsState()
    val container = containers.find { it.id == containerId } ?: return
    MountScreenContent(container, viewModel, onBack, onMountSuccess)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MountScreenContent(
    container: ContainerEntity,
    viewModel: VaultViewModel,
    onBack: () -> Unit,
    onMountSuccess: (id: String) -> Unit
) {
    val context            = LocalContext.current
    val focusManager       = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val mountState by viewModel.mountState.collectAsState()
    val mountLogs  by viewModel.mountLogs.collectAsState()
    val mountId    = container.id

    var keyfiles by remember { mutableStateOf<List<KeyfileEntry>>(emptyList()) }
    var hiddenKeyfiles by remember { mutableStateOf<List<KeyfileEntry>>(emptyList()) }

    // Keyfile reads are off the main thread: a keyfile can sit behind a network-backed
    // provider, where both the name query and the read block for as long as it takes.
    val keyfileScope = rememberCoroutineScope()
    val keyfilePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        keyfileScope.launch {
            val (bytes, name) = withContext(Dispatchers.IO) { FileUtils.readKeyfileBytes(context, uri) } ?: return@launch
            keyfiles = keyfiles + KeyfileEntry(bytes, name, uri.toString())
        }
    }
    val hiddenKeyfilePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        keyfileScope.launch {
            val (bytes, name) = withContext(Dispatchers.IO) { FileUtils.readKeyfileBytes(context, uri) } ?: return@launch
            hiddenKeyfiles = hiddenKeyfiles + KeyfileEntry(bytes, name, uri.toString())
        }
    }

    var isMounting                      by remember { mutableStateOf(false) }
    var biometricKeyfileMissing         by remember { mutableStateOf(false) }
    /* Same overlay, different reason: this vault protects a hidden volume, so the
       fingerprint alone cannot open it - the hidden password is not saved with it.
       Which reason it is outlives the dismissal, so the text does not change under
       the fade-out. */
    var biometricNeedsHiddenPassword    by remember { mutableStateOf(false) }
    var bioBailOutIsProtection          by remember { mutableStateOf(false) }
    var hiddenProtectionMountSuccessId  by remember { mutableStateOf<String?>(null) }

    // Close all open PFDs if the composable leaves composition without explicit cleanup.
    val keyfilesRef       = rememberUpdatedState(keyfiles)
    val hiddenKeyfilesRef = rememberUpdatedState(hiddenKeyfiles)
    DisposableEffect(Unit) {
        onDispose {
            keyfilesRef.value.forEach { it.zero() }
            hiddenKeyfilesRef.value.forEach { it.zero() }
        }
    }

    // ── Form state ────────────────────────────────────────────────────────
    val passwordState       = remember { mutableStateOf("") }
    var password            by passwordState
    val hiddenPasswordState = remember { mutableStateOf("") }

    val lifecycleOwner               = LocalLifecycleOwner.current
    val passwordFocusRequester       = remember { FocusRequester() }
    val hiddenPasswordFocusRequester = remember { FocusRequester() }

    var refocusCount by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refocusCount++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(refocusCount) {
        if (refocusCount == 0) return@LaunchedEffect
        delay(200)
        focusManager.clearFocus()
        delay(50)
        passwordFocusRequester.requestFocus()
        keyboardController?.show()
    }
    var showPassword   by remember { mutableStateOf(false) }
    /*
     * Opened when this vault is remembered as mounting a way the user chose, so read-only
     * or hidden-volume protection is visible rather than applied from behind a collapsed
     * section.
     *
     * The remembered PRF is deliberately NOT one of those. It is read out of the header
     * after a mount succeeds rather than picked by anyone (#148), so every vault opened
     * even once has one - which left Advanced expanded on all of them, on the strength of
     * a value nobody chose. It also changes nothing about the mount except how long the
     * unlock takes.
     */
    var showAdvanced  by rememberSaveable(mountId) {
        mutableStateOf(container.mountReadOnly || container.mountProtectHidden)
    }
    var pimValue      by remember { mutableStateOf("") }
    var showPim       by remember { mutableStateOf(false) }
    /*
     * Seeded from what this vault was last opened with (#148), and saveable so a rotation
     * does not quietly drop the choice either - both were plain `remember` before, which is
     * why read-only did not survive so much as turning the phone.
     */
    var readOnly           by rememberSaveable(mountId) { mutableStateOf(container.mountReadOnly) }
    var protectHidden      by rememberSaveable(mountId) { mutableStateOf(container.mountProtectHidden) }
    var hiddenPassword     by hiddenPasswordState
    var showHiddenPassword by remember { mutableStateOf(false) }
    var hiddenPimValue     by remember { mutableStateOf("") }
    var showHiddenPim      by remember { mutableStateOf(false) }
    var shakeKey      by remember { mutableIntStateOf(0) }
    val shakeAnim     = remember { Animatable(0f) }

    /* The PRF the volume turned out to be last time, not a guess: mounting with it named
     * skips the auto-detect that costs seconds on every unlock. */
    var selectedHash by rememberSaveable(mountId) { mutableIntStateOf(container.mountHashId) }
    /* The hidden volume's PRF is a per-attempt choice, like its password and PIM: nothing
       about the hidden volume is remembered between mounts. Auto here means the same five
       PBKDF2 hashes the vault itself is scanned with - not Argon2id, which is why a hidden
       volume made with it has to be named (#177). */
    var selectedHiddenHash by rememberSaveable(mountId) { mutableIntStateOf(VeraCryptEngine.HASH_AUTO) }
    /* Argon2id is in the list even though auto-detect never reaches it: naming it
       here is the only way to open such a volume the first time (#177). */
    val hashes = remember { listOf(-1 to "Auto") + (0..5).map { it to VeraCryptEngine.hashIdToString(it) } }

    // ── Biometric state ───────────────────────────────────────────────────
    val hasBiometricSaved  = remember(mountId) { viewModel.hasBiometricCredentials(mountId) }
    val biometricAvailable = remember(mountId) { viewModel.isBiometricAvailable() }
    val bioModeState           = remember { mutableStateOf(if (hasBiometricSaved) BioUiMode.Indicator else BioUiMode.Form) }
    var bioMode                by bioModeState
    val biometricEnabledState  = remember { mutableStateOf(hasBiometricSaved) }
    var biometricEnabled       by biometricEnabledState
    var localHasBiometricSaved by remember { mutableStateOf(hasBiometricSaved) }
    val isDecryptModeState     = remember { mutableStateOf(false) }
    val pendingEncryptState    = remember { mutableStateOf<EncryptPending?>(null) }
    var showRemoveBioDialog    by remember { mutableStateOf(false) }

    // ── Biometric prompt setup ────────────────────────────────────────────
    val activity = LocalContext.current as FragmentActivity

    /* What the last attempt was made with, so that the Argon2id offer on a failed
       mount can repeat it with that PRF named instead of asking for everything again
       (#177). Cleared when the error is dismissed. */
    var lastAttempt by remember { mutableStateOf<MountAttempt?>(null) }
    /* Set only by the "try anyway" action on a memory refusal, and cleared by the
       attempt it belongs to, so it can never leak into a later unlock (#177). */
    var allowLowMemoryOnce by remember { mutableStateOf(false) }

    val onUnlock: (String, Int, Int, String?, Int, List<ByteArray>, Int) -> Unit =
            { pw, pim, hash, protectPw, protectPim, protectKeyfileData, protectHash ->
        isMounting = true
        lastAttempt = MountAttempt(pw, pim, hash, protectPw, protectPim, protectKeyfileData, protectHash)
        /* Read by the call below and immediately dropped: the exception the user made
           for one attempt must not quietly apply to the next one. */
        viewModel.mountContainer(
            container                 = container,
            password                  = pw,
            keyfileData               = keyfiles.map { it.content },
            pim                       = pim,
            algorithm                 = VeraCryptEngine.ALGO_AUTO,
            hashAlgorithm             = hash,
            protectHiddenPassword     = protectPw,
            protectHiddenPim          = protectPim,
            protectHiddenKeyfileData  = protectKeyfileData,
            protectHiddenHash         = protectHash,
            readOnly                  = readOnly,
            allowLowMemory            = allowLowMemoryOnce.also { allowLowMemoryOnce = false },
            onSuccess = { id ->
                keyfiles.forEach { it.zero() }
                hiddenKeyfiles.forEach { it.zero() }
                keyfiles       = emptyList()
                hiddenKeyfiles = emptyList()
                isMounting     = false
                /* Sound because a mount that could not establish the boundary no longer
                   succeeds: the native side refuses it and the ViewModel unmounts anything
                   that somehow got past. Before that, this shield was shown to everyone who
                   typed a hidden password, protected or not. */
                if (!protectPw.isNullOrBlank()) {
                    hiddenProtectionMountSuccessId = id
                } else {
                    onMountSuccess(id)
                }
            }
        )
    }
    val latestOnUnlock      = rememberUpdatedState(onUnlock)
    val latestContainer     = rememberUpdatedState(container)
    val latestOnMountSuccess = rememberUpdatedState(onMountSuccess)
    val latestOnSaveBio     = rememberUpdatedState<(Cipher, String, Int) -> Unit> { cipher, pw, pim ->
        viewModel.saveBiometricCredentials(mountId, cipher, pw, pim)
        viewModel.saveKeyfileUrisForBiometric(mountId, keyfiles.map { it.uriString })
    }

    val biometricCallback = remember {
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val cipher = result.cryptoObject?.cipher ?: return
                if (isDecryptModeState.value) {
                    isMounting = true
                    viewModel.biometricMountContainer(
                        container             = latestContainer.value,
                        cipher                = cipher,
                        onMissingKeyfiles     = {
                            isMounting              = false
                            bioModeState.value      = BioUiMode.Cancelled
                            biometricEnabledState.value = false
                            bioBailOutIsProtection  = false
                            biometricKeyfileMissing = true
                        },
                        onInvalidCredentials  = {
                            isMounting              = false
                            bioModeState.value      = BioUiMode.Cancelled
                            biometricEnabledState.value = false
                        },
                        onProtectionNeedsPassword = {
                            isMounting              = false
                            bioModeState.value      = BioUiMode.Cancelled
                            biometricEnabledState.value = false
                            bioBailOutIsProtection  = true
                            biometricNeedsHiddenPassword = true
                        },
                        onSuccess             = { id ->
                            isMounting = false
                            latestOnMountSuccess.value(id)
                        }
                    )
                } else {
                    val data = pendingEncryptState.value ?: return
                    latestOnSaveBio.value(cipher, data.password, data.pim)
                    latestOnUnlock.value(data.password, data.pim, data.hash, data.protectHidden,
                                         data.protectHiddenPim, data.protectHiddenKeyfileData,
                                         data.protectHiddenHash)
                    pendingEncryptState.value = null
                }
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (isDecryptModeState.value) {
                    bioModeState.value          = BioUiMode.Cancelled
                    biometricEnabledState.value = false
                } else {
                    pendingEncryptState.value?.let { data ->
                        latestOnUnlock.value(data.password, data.pim, data.hash, data.protectHidden,
                                             data.protectHiddenPim, data.protectHiddenKeyfileData,
                                             data.protectHiddenHash)
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

    LaunchedEffect(Unit) {
        if (!hasBiometricSaved) return@LaunchedEffect
        val cryptoObj = viewModel.getBiometricCryptoObjectForDecrypt(mountId)
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

    val pim       = pimValue.toIntOrNull() ?: 0
    val isError   = mountState is VaultViewModel.MountState.Error
    val isLoading = mountState is VaultViewModel.MountState.Loading

    // One action, two callers no more: the top bar's icon is gone and the button at the
    // bottom of the form does this. Hoisted rather than duplicated - it is the whole
    // biometric-enrolment dance, and two copies would drift.
    // The lock is the only way to mount now, so it has to advertise itself: a small hop
    // every few seconds while the screen sits idle, and a shake when it is tapped with
    // nothing to unlock with.
    val notifications = LocalNotifications.current

    // Shown once, to new installs and to anyone who updates into the moved control. The
    // flag defaults to true so a slow read of the preference cannot flash the dialog at
    // someone who has already dismissed it.
    val mountHintShown by viewModel.mountHintShown.collectAsState()
    var showMountHint  by remember { mutableStateOf(false) }
    LaunchedEffect(mountHintShown) { if (!mountHintShown) showMountHint = true }
    val lockHop   = remember { Animatable(0f) }
    val lockShake = remember { Animatable(0f) }
    var shakeTrigger by remember { mutableIntStateOf(0) }
    val haptics    = LocalHapticFeedback.current

    LaunchedEffect(isLoading, bioMode) {
        if (isLoading || bioMode != BioUiMode.Form) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(4000)
            lockHop.animateTo(-26f, tween(200, easing = LinearOutSlowInEasing))
            lockHop.animateTo(0f, spring(dampingRatio = 0.3f, stiffness = 700f))
        }
    }

    LaunchedEffect(shakeTrigger) {
        if (shakeTrigger == 0) return@LaunchedEffect
        repeat(3) {
            lockShake.animateTo(12f, tween(50))
            lockShake.animateTo(-12f, tween(50))
        }
        lockShake.animateTo(0f, tween(50))
    }

    val doMount: () -> Unit = {
        focusManager.clearFocus()
        keyboardController?.hide()
        val protectedPassword = if (protectHidden && hiddenPassword.isNotBlank()) hiddenPassword else null
        val protectedPim = if (protectHidden) (hiddenPimValue.toIntOrNull() ?: 0) else 0
        val protectedKeyfileData = if (protectHidden) hiddenKeyfiles.map { it.content } else emptyList()
        val protectedHash = if (protectHidden) selectedHiddenHash else VeraCryptEngine.HASH_AUTO
        if (biometricEnabled) {
            val cryptoObj = viewModel.getBiometricCryptoObjectForEncrypt()
            if (cryptoObj != null) {
                isDecryptModeState.value  = false
                pendingEncryptState.value = EncryptPending(
                    password                  = password,
                    pim                       = pim,
                    hash                      = selectedHash,
                    protectHidden             = protectedPassword,
                    protectHiddenPim          = protectedPim,
                    protectHiddenKeyfileData  = protectedKeyfileData,
                    protectHiddenHash         = protectedHash
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
                latestOnUnlock.value(password, pim, selectedHash, protectedPassword, protectedPim,
                                     protectedKeyfileData, protectedHash)
            }
        } else {
            latestOnUnlock.value(password, pim, selectedHash, protectedPassword, protectedPim,
                                 protectedKeyfileData, protectedHash)
        }
    }
    /* Protection on with an empty hidden password used to mount the vault with no
       protection at all - the toggle was read, the empty field was not. Nothing is sent
       to the engine in that state, so the refusal has to happen here. */
    val protectionIncomplete = protectHidden && hiddenPassword.isBlank()
    val canUnlock = bioMode == BioUiMode.Form && (password.isNotEmpty() || keyfiles.isNotEmpty()) &&
                    !isLoading && !protectionIncomplete

    BackHandler(enabled = !isMounting) {
        keyfiles.forEach { it.zero() }
        hiddenKeyfiles.forEach { it.zero() }
        keyfiles       = emptyList()
        hiddenKeyfiles = emptyList()
        viewModel.resetMountState()
        onBack()
    }

    if (showRemoveBioDialog) {
        AppDialog(
            onDismissRequest = { showRemoveBioDialog = false; biometricEnabled = true },
            title            = { Text(stringResource(R.string.vault_remove_biometric_title)) },
            text             = { Text(stringResource(R.string.vault_remove_biometric_body, container.name)) },
            confirmButton    = {
                TextButton(onClick = {
                    showRemoveBioDialog    = false
                    viewModel.deleteBiometricCredentials(mountId)
                    localHasBiometricSaved = false
                    biometricEnabled       = false
                    bioMode                = BioUiMode.Form
                }) { Text(stringResource(R.string.vault_remove_confirm)) }
            },
            dismissButton    = {
                TextButton(onClick = { showRemoveBioDialog = false; biometricEnabled = true }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.mount_screen_title), fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(onClick = {
                            keyfiles.forEach { it.zero() }
                            hiddenKeyfiles.forEach { it.zero() }
                            keyfiles       = emptyList()
                            hiddenKeyfiles = emptyList()
                            viewModel.resetMountState()
                            onBack()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                        }
                    },
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding())
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ── Hero ─────────────────────────────────────────────────────
                Spacer(Modifier.height(32.dp))
                // Only where it does something. The biometric states have their own
                // controls - a fingerprint circle while the prompt is up, "try again" and
                // "use password" after it is dismissed - and a lock that cannot be pressed
                // sitting above them reads as a broken button.
                val unlockLabel = stringResource(R.string.mount_cd_unlock)
                if (bioMode == BioUiMode.Form) Box(
                    modifier         = Modifier
                        .offset { IntOffset(lockShake.value.roundToInt(), lockHop.value.roundToInt()) }
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable(enabled = !isLoading) {
                            if (canUnlock) {
                                doMount()
                            } else {
                                // Refusing quietly would leave the tap looking broken. The
                                // shake, the buzz and the banner all say the same thing
                                // three ways, because one of them is the one that lands.
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                notifications.notify(InAppNotification.MountNeedsCredentials)
                                shakeTrigger++
                            }
                        }
                        .semantics { contentDescription = unlockLabel },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.Lock,
                        contentDescription = null,
                        tint     = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                }
                if (bioMode == BioUiMode.Form) Spacer(Modifier.height(16.dp))
                Text(
                    text       = container.name,
                    style      = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign  = TextAlign.Center
                )
                Spacer(Modifier.height(32.dp))

                // ── Form body ─────────────────────────────────────────────────
                when (bioMode) {
                    BioUiMode.Indicator -> {
                        Column(
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

                    BioUiMode.Cancelled -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                stringResource(R.string.vault_biometric_failed),
                                style     = MaterialTheme.typography.bodyMedium,
                                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(4.dp))
                            TextButton(onClick = {
                                val cryptoObj = viewModel.getBiometricCryptoObjectForDecrypt(mountId)
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
                            TextButton(onClick = { bioMode = BioUiMode.Form }) {
                                Text(stringResource(R.string.vault_biometric_use_password))
                            }
                        }
                    }

                    BioUiMode.Form -> {
                        Column(
                            modifier            = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value                = password,
                                onValueChange        = { password = it },
                                label                = { Text(stringResource(R.string.common_password)) },
                                singleLine           = true,
                                isError              = isError,
                                supportingText       = if (isError) { { Text(stringResource(R.string.vault_mount_wrong_password)) } } else null,
                                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon         = {
                                    IconButton(onClick = { showPassword = !showPassword }) {
                                        Icon(
                                            if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                            contentDescription = null
                                        )
                                    }
                                },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction    = ImeAction.Next
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .offset { IntOffset(shakeAnim.value.roundToInt(), 0) }
                                    .focusRequester(passwordFocusRequester)
                            )

                            OutlinedTextField(
                                value         = pimValue,
                                onValueChange = {
                                    if (it.all { c -> c.isDigit() } && it.length <= 7) {
                                        val v = it.toLongOrNull() ?: 0L
                                        if (it.isEmpty() || v in 1L..2_147_468L) pimValue = it
                                    }
                                },
                                label                = { Text(stringResource(R.string.vault_mount_pim_label)) },
                                placeholder          = { Text(stringResource(R.string.vault_mount_pim_placeholder)) },
                                visualTransformation = if (showPim) VisualTransformation.None else DotVisualTransformation(),
                                trailingIcon         = {
                                    IconButton(onClick = { showPim = !showPim }) {
                                        Icon(
                                            if (showPim) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                            contentDescription = null
                                        )
                                    }
                                },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction    = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        if (canUnlock) latestOnUnlock.value(
                                            password, pim, selectedHash,
                                            if (protectHidden && hiddenPassword.isNotBlank()) hiddenPassword else null,
                                            if (protectHidden) (hiddenPimValue.toIntOrNull() ?: 0) else 0,
                                            if (protectHidden) hiddenKeyfiles.map { it.content } else emptyList(),
                                            if (protectHidden) selectedHiddenHash else VeraCryptEngine.HASH_AUTO)
                                    }
                                ),
                                singleLine = true,
                                modifier   = Modifier.fillMaxWidth()
                            )

                            PrfPicker(
                                hashes   = hashes,
                                selected = selectedHash,
                                label    = stringResource(R.string.vault_mount_hash),
                                onSelect = { selectedHash = it }
                            )

                            keyfiles.forEachIndexed { index, entry ->
                                Row(
                                    modifier          = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(ArcanumIcons.Keyfile, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(entry.displayName, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                    IconButton(
                                        onClick  = {
                                            val updated = keyfiles.toMutableList()
                                            updated[index].zero()
                                            updated.removeAt(index)
                                            keyfiles = updated
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Outlined.Close, null, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                            TextButton(
                                onClick  = { keyfilePickerLauncher.launch(arrayOf("*/*")) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(ArcanumIcons.Keyfile, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.vault_mount_add_keyfile), style = MaterialTheme.typography.labelMedium)
                            }

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
                                    if (biometricAvailable) {
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
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { val v = !readOnly; readOnly = v; if (v) protectHidden = false }
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Outlined.AutoStories,
                                            contentDescription = null,
                                            tint     = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(stringResource(R.string.vault_mount_read_only), style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                stringResource(R.string.vault_mount_read_only_desc),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Switch(
                                            checked         = readOnly,
                                            onCheckedChange = { readOnly = it; if (it) protectHidden = false }
                                        )
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .then(if (!readOnly) Modifier.clickable { protectHidden = !protectHidden } else Modifier)
                                            .alpha(if (readOnly) 0.38f else 1f)
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Outlined.Shield,
                                            contentDescription = null,
                                            tint     = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(stringResource(R.string.vault_mount_protect_hidden), style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                stringResource(R.string.vault_mount_protect_hidden_desc),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Switch(
                                            checked         = protectHidden,
                                            onCheckedChange = { if (!readOnly) protectHidden = it },
                                            enabled         = !readOnly
                                        )
                                    }
                                    AnimatedVisibility(visible = protectHidden) {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedTextField(
                                                value                = hiddenPassword,
                                                onValueChange        = { hiddenPassword = it },
                                                label                = { Text(stringResource(R.string.vault_mount_hidden_password)) },
                                                isError              = protectionIncomplete,
                                                supportingText       = if (protectionIncomplete) {
                                                    { Text(stringResource(R.string.vault_mount_hidden_password_required)) }
                                                } else null,
                                                singleLine           = true,
                                                visualTransformation = if (showHiddenPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                                trailingIcon         = {
                                                    IconButton(onClick = { showHiddenPassword = !showHiddenPassword }) {
                                                        Icon(
                                                            if (showHiddenPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                                            contentDescription = null
                                                        )
                                                    }
                                                },
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .focusRequester(hiddenPasswordFocusRequester)
                                            )
                                            OutlinedTextField(
                                                value         = hiddenPimValue,
                                                onValueChange = {
                                                    if (it.all { c -> c.isDigit() } && it.length <= 7) {
                                                        val v = it.toLongOrNull() ?: 0L
                                                        if (it.isEmpty() || v in 1L..2_147_468L) hiddenPimValue = it
                                                    }
                                                },
                                                label                = { Text(stringResource(R.string.vault_mount_pim_label)) },
                                                placeholder          = { Text(stringResource(R.string.vault_mount_pim_placeholder)) },
                                                visualTransformation = if (showHiddenPim) VisualTransformation.None else DotVisualTransformation(),
                                                trailingIcon         = {
                                                    IconButton(onClick = { showHiddenPim = !showHiddenPim }) {
                                                        Icon(if (showHiddenPim) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = null)
                                                    }
                                                },
                                                keyboardOptions      = KeyboardOptions(
                                                    keyboardType = KeyboardType.Number,
                                                    imeAction    = ImeAction.Done
                                                ),
                                                singleLine = true,
                                                modifier   = Modifier.fillMaxWidth()
                                            )
                                            PrfPicker(
                                                hashes   = hashes,
                                                selected = selectedHiddenHash,
                                                label    = stringResource(R.string.vault_mount_hidden_hash),
                                                onSelect = { selectedHiddenHash = it }
                                            )
                                            hiddenKeyfiles.forEachIndexed { index, entry ->
                                                Row(
                                                    modifier          = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(ArcanumIcons.Keyfile, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                                    Spacer(Modifier.width(6.dp))
                                                    Text(entry.displayName, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                                    IconButton(
                                                        onClick  = {
                                                            val updated = hiddenKeyfiles.toMutableList()
                                                            updated[index].zero()
                                                            updated.removeAt(index)
                                                            hiddenKeyfiles = updated
                                                        },
                                                        modifier = Modifier.size(32.dp)
                                                    ) {
                                                        Icon(Icons.Outlined.Close, null, modifier = Modifier.size(16.dp))
                                                    }
                                                }
                                            }
                                            TextButton(
                                                onClick  = { hiddenKeyfilePickerLauncher.launch(arrayOf("*/*")) },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Icon(ArcanumIcons.Keyfile, null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text(stringResource(R.string.vault_mount_add_keyfile_hidden), style = MaterialTheme.typography.labelMedium)
                                            }
                                        }
                                    }
                                }
                            }

                        }
                    }
                }
            }
        }

        // ── First-visit hint ──────────────────────────────────────────────────
        if (showMountHint) {
            AppDialog(
                onDismissRequest = { showMountHint = false; viewModel.markMountHintShown() },
                title            = { Text(stringResource(R.string.mount_hint_title)) },
                text             = { Text(stringResource(R.string.mount_hint_body)) },
                confirmButton    = {
                    TextButton(onClick = {
                        showMountHint = false
                        viewModel.markMountHintShown()
                    }) { Text(stringResource(R.string.mount_hint_ok)) }
                }
            )
        }

        // ── Mounting overlay ──────────────────────────────────────────────────
        if (isMounting) {
            val errorState = mountState as? VaultViewModel.MountState.Error
            Box(Modifier.fillMaxSize().zIndex(100f)) {
                MountingOverlay(
                    isError             = errorState != null,
                    errorMessage        = errorState?.message,
                    showCredentialHints = errorState?.credentialHint ?: true,
                    logs                = mountLogs,
                    onCancel            = { viewModel.cancelMount(); onBack() },
                    onDismissError      = {
                        viewModel.resetMountState(); isMounting = false
                        lastAttempt = null; allowLowMemoryOnce = false
                    },
                    /* Whichever volume the offer is about, the other one's PRF is repeated as
                       it was: naming Argon2id for the hidden volume must not turn the vault's
                       own PRF into a second guess, and the other way round. */
                    onTryArgon2         = lastAttempt?.takeIf { errorState?.argon2Offer == true }?.let { a ->
                        {
                            viewModel.resetMountState()
                            allowLowMemoryOnce = false
                            val forHidden = errorState?.argon2OfferIsHidden == true
                            onUnlock(a.password, a.pim,
                                     if (forHidden) a.hash else VeraCryptEngine.HASH_ARGON2ID,
                                     a.protectPassword, a.protectPim, a.protectKeyfiles,
                                     if (forHidden) VeraCryptEngine.HASH_ARGON2ID else a.protectHash)
                        }
                    },
                    argon2OfferIsHidden = errorState?.argon2OfferIsHidden == true,
                    onTryAnyway         = lastAttempt?.takeIf { errorState?.argon2LowMemoryRetry == true }?.let { a ->
                        {
                            viewModel.resetMountState()
                            allowLowMemoryOnce = true
                            onUnlock(a.password, a.pim, a.hash,
                                     a.protectPassword, a.protectPim, a.protectKeyfiles,
                                     a.protectHash)
                        }
                    }
                )
            }
        }

        // ── Hidden volume protection success overlay ──────────────────────────
        AnimatedVisibility(
            visible  = hiddenProtectionMountSuccessId != null,
            enter    = fadeIn(animationSpec = tween(250)),
            exit     = fadeOut(animationSpec = tween(300)),
            modifier = Modifier.fillMaxSize().zIndex(99f)
        ) {
            val shieldComposition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.shield))
            val shieldProgress    by animateLottieCompositionAsState(shieldComposition, iterations = 1)
            BackHandler(enabled = hiddenProtectionMountSuccessId != null) {
                hiddenProtectionMountSuccessId?.let { latestOnMountSuccess.value(it) }
                hiddenProtectionMountSuccessId = null
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black)
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
                    LottieAnimation(shieldComposition, { shieldProgress }, modifier = Modifier.size(180.dp))
                    Spacer(Modifier.height(36.dp))
                    Text(
                        text       = stringResource(R.string.vault_outer_protected_title),
                        style      = MaterialTheme.typography.titleLarge,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        color      = androidx.compose.ui.graphics.Color.White,
                        textAlign  = TextAlign.Center
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text      = stringResource(R.string.vault_outer_protected_body),
                        style     = MaterialTheme.typography.bodyMedium,
                        color     = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center
                    )
                }
                androidx.compose.material3.Button(
                    onClick  = {
                        hiddenProtectionMountSuccessId?.let { latestOnMountSuccess.value(it) }
                        hiddenProtectionMountSuccessId = null
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 24.dp)
                ) {
                    Text(stringResource(R.string.common_done), style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        // ── Biometric bail-out overlay (keyfiles gone, or protection needs the password) ──
        val bioBailOut = biometricKeyfileMissing || biometricNeedsHiddenPassword
        val dismissBioBailOut = {
            biometricKeyfileMissing = false
            biometricNeedsHiddenPassword = false
            bioMode = BioUiMode.Form
        }
        AnimatedVisibility(
            visible  = bioBailOut,
            enter    = fadeIn(animationSpec = tween(250)),
            exit     = fadeOut(animationSpec = tween(300)),
            modifier = Modifier.fillMaxSize().zIndex(99f)
        ) {
            val errorComposition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.error))
            val errorProgress    by animateLottieCompositionAsState(errorComposition, iterations = 1)
            BackHandler(enabled = bioBailOut) { dismissBioBailOut() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black)
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
                    LottieAnimation(errorComposition, { errorProgress }, modifier = Modifier.size(180.dp))
                    Spacer(Modifier.height(36.dp))
                    Text(
                        text       = stringResource(
                            if (bioBailOutIsProtection) R.string.vault_biometric_protection_title
                            else R.string.vault_biometric_keyfile_missing_title
                        ),
                        style      = MaterialTheme.typography.titleLarge,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        color      = androidx.compose.ui.graphics.Color.White,
                        textAlign  = TextAlign.Center
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text      = stringResource(
                            if (bioBailOutIsProtection) R.string.vault_biometric_protection_body
                            else R.string.vault_biometric_keyfile_missing_body
                        ),
                        style     = MaterialTheme.typography.bodyMedium,
                        color     = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center
                    )
                }
                androidx.compose.material3.Button(
                    onClick  = dismissBioBailOut,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 24.dp)
                ) {
                    Text(stringResource(R.string.common_done), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/**
 * The credentials of the attempt just made, kept only so a failed mount can be repeated
 * with Argon2id named (#177). The password is already in the field the user typed it
 * into for as long as this screen is up, so this holds nothing that was not there
 * anyway, and it is dropped as soon as the error is dismissed.
 */
private data class MountAttempt(
    val password: String,
    val pim: Int,
    val hash: Int,
    val protectPassword: String?,
    val protectPim: Int,
    val protectKeyfiles: List<ByteArray>,
    val protectHash: Int
)

/**
 * The PRF dropdown, used for the vault itself and for the hidden volume being protected.
 * Both lists are the same six plus Auto - a hidden volume is a volume, and the one that
 * Auto cannot find is the same one in both places (Argon2id, #177).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrfPicker(
    hashes: List<Pair<Int, String>>,
    selected: Int,
    label: String,
    onSelect: (Int) -> Unit
) {
    var showSheet by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value         = hashes.first { it.first == selected }.second,
            onValueChange = {},
            readOnly      = true,
            label         = { Text(label) },
            trailingIcon  = { Icon(Icons.Outlined.ExpandMore, contentDescription = null) },
            modifier      = Modifier.fillMaxWidth()
        )
        Box(Modifier.matchParentSize().clickable { showSheet = true })
    }
    if (showSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        AppSheet(
            onDismissRequest = { showSheet = false },
            sheetState       = sheetState
        ) {
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                Text(
                    text       = label,
                    style      = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier   = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
                hashes.forEach { (id, itemLabel) ->
                    Row(
                        modifier          = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(id); showSheet = false }
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected == id,
                            onClick  = { onSelect(id); showSheet = false }
                        )
                        Text(
                            text     = itemLabel,
                            style    = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
