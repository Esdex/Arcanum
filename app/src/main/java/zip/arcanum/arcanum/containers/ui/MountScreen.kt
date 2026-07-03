package zip.arcanum.arcanum.containers.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.outlined.HideSource
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Switch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.rememberModalBottomSheetState
import zip.arcanum.core.components.AppSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalAutofill
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofillTree
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
import zip.arcanum.R
import zip.arcanum.core.icons.ArcanumIcons
import zip.arcanum.core.components.AppDialog
import zip.arcanum.core.database.entities.ContainerEntity
import zip.arcanum.core.utils.FileUtils
import zip.arcanum.crypto.VeraCryptEngine
import javax.crypto.Cipher
import kotlin.math.roundToInt

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
    val protectHiddenKeyfileData: List<ByteArray>
)

@Composable
fun MountScreen(
    containerId: String,
    viewModel: VaultViewModel,
    startBiometricSetup: Boolean = false,
    onBack: () -> Unit,
    onMountSuccess: (id: String) -> Unit
) {
    val containers by viewModel.containers.collectAsState()
    val container = containers.find { it.id == containerId } ?: return
    MountScreenContent(container, viewModel, startBiometricSetup, onBack, onMountSuccess)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun MountScreenContent(
    container: ContainerEntity,
    viewModel: VaultViewModel,
    startBiometricSetup: Boolean,
    onBack: () -> Unit,
    onMountSuccess: (id: String) -> Unit
) {
    val context            = LocalContext.current
    val focusManager       = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val mountState by viewModel.mountState.collectAsState()
    val mountLogs  by viewModel.mountLogs.collectAsState()
    val biometricUnlockEnabled by viewModel.biometricUnlockEnabled.collectAsState()
    val mountId    = container.id

    var keyfiles by remember { mutableStateOf<List<KeyfileEntry>>(emptyList()) }
    var hiddenKeyfiles by remember { mutableStateOf<List<KeyfileEntry>>(emptyList()) }

    val keyfilePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val (bytes, name) = FileUtils.readKeyfileBytes(context, uri) ?: return@rememberLauncherForActivityResult
        keyfiles = keyfiles + KeyfileEntry(bytes, name, uri.toString())
    }
    val hiddenKeyfilePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val (bytes, name) = FileUtils.readKeyfileBytes(context, uri) ?: return@rememberLauncherForActivityResult
        hiddenKeyfiles = hiddenKeyfiles + KeyfileEntry(bytes, name, uri.toString())
    }

    var isMounting                      by remember { mutableStateOf(false) }
    var biometricKeyfileMissing         by remember { mutableStateOf(false) }
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

    val autofill               = LocalAutofill.current
    val autofillManager        = remember { context.getSystemService(android.view.autofill.AutofillManager::class.java) }
    val lifecycleOwner         = LocalLifecycleOwner.current
    val autofillScope          = rememberCoroutineScope()
    val passwordFocusRequester       = remember { FocusRequester() }
    val hiddenPasswordFocusRequester = remember { FocusRequester() }
    val passwordAutofillNode = remember {
        AutofillNode(listOf(AutofillType.Password)) { passwordState.value = it }
    }
    val hiddenPasswordAutofillNode = remember {
        AutofillNode(listOf(AutofillType.Password)) { hiddenPasswordState.value = it }
    }
    val autofillTree = LocalAutofillTree.current

    // Only the currently focused field's node lives in the tree at any time.
    // This prevents KeePassDX from caching a mapping to the wrong node after auth.
    var focusedField by remember { mutableStateOf("password") }
    if (focusedField == "hidden") {
        autofillTree.children.remove(passwordAutofillNode.id)
        autofillTree += hiddenPasswordAutofillNode
    } else {
        autofillTree.children.remove(hiddenPasswordAutofillNode.id)
        autofillTree += passwordAutofillNode
    }
    DisposableEffect(Unit) {
        onDispose {
            autofillTree.children.remove(passwordAutofillNode.id)
            autofillTree.children.remove(hiddenPasswordAutofillNode.id)
        }
    }

    var refocusCount by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                autofillManager?.cancel()
                refocusCount++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(refocusCount) {
        if (refocusCount == 0) return@LaunchedEffect
        delay(200)
        focusManager.clearFocus()
        delay(50)
        if (focusedField == "hidden") hiddenPasswordFocusRequester.requestFocus()
        else passwordFocusRequester.requestFocus()
        keyboardController?.show()
    }
    var showPassword   by remember { mutableStateOf(false) }
    var showHashSheet  by remember { mutableStateOf(false) }
    var showAdvanced  by remember { mutableStateOf(false) }
    var pimValue      by remember { mutableStateOf("") }
    var showPim       by remember { mutableStateOf(false) }
    var protectHidden      by remember { mutableStateOf(false) }
    var hiddenPassword     by hiddenPasswordState
    var showHiddenPassword by remember { mutableStateOf(false) }
    var hiddenPimValue     by remember { mutableStateOf("") }
    var showHiddenPim      by remember { mutableStateOf(false) }
    var shakeKey      by remember { mutableIntStateOf(0) }
    val shakeAnim     = remember { Animatable(0f) }

    var selectedHash by rememberSaveable { mutableIntStateOf(VeraCryptEngine.HASH_AUTO) }
    val hashes = remember {
        listOf(VeraCryptEngine.HASH_AUTO to "Auto") +
            (0..VeraCryptEngine.HASH_ARGON2ID).map { it to VeraCryptEngine.hashIdToString(it) }
    }

    // ── Biometric state ───────────────────────────────────────────────────
    val hasBiometricSaved  = remember(mountId) { viewModel.hasBiometricCredentials(mountId) }
    val biometricAvailable = biometricUnlockEnabled && remember(mountId) { viewModel.isBiometricAvailable() }
    val bioModeState           = remember(mountId, biometricUnlockEnabled) {
        mutableStateOf(if (hasBiometricSaved && biometricUnlockEnabled) BioUiMode.Indicator else BioUiMode.Form)
    }
    var bioMode                by bioModeState
    val biometricEnabledState  = remember(mountId, biometricUnlockEnabled, startBiometricSetup) {
        mutableStateOf((hasBiometricSaved && biometricUnlockEnabled) || (startBiometricSetup && biometricAvailable && !hasBiometricSaved))
    }
    var biometricEnabled       by biometricEnabledState
    var localHasBiometricSaved by remember { mutableStateOf(hasBiometricSaved) }
    val isDecryptModeState     = remember { mutableStateOf(false) }
    val pendingEncryptState    = remember { mutableStateOf<EncryptPending?>(null) }
    var showRemoveBioDialog    by remember { mutableStateOf(false) }

    // ── Biometric prompt setup ────────────────────────────────────────────
    val activity = LocalActivity.current as FragmentActivity

    val onUnlock: (String, Int, Int, String?, Int, List<ByteArray>) -> Unit = { pw, pim, hash, protectPw, protectPim, protectKeyfileData ->
        isMounting = true
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
            onSuccess = { id ->
                keyfiles.forEach { it.zero() }
                hiddenKeyfiles.forEach { it.zero() }
                keyfiles       = emptyList()
                hiddenKeyfiles = emptyList()
                isMounting     = false
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
    val latestOnSaveBio     = rememberUpdatedState<(Cipher, EncryptPending) -> Unit> { cipher, data ->
        viewModel.saveBiometricCredentials(
            containerId    = mountId,
            cipher         = cipher,
            password       = data.password,
            pim            = data.pim,
            algorithm      = VeraCryptEngine.ALGO_AUTO,
            hashAlgorithm  = data.hash,
            keyfileUris    = keyfiles.map { it.uriString }
        )
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
                            biometricKeyfileMissing = true
                        },
                        onInvalidCredentials  = {
                            isMounting              = false
                            bioModeState.value      = BioUiMode.Cancelled
                            biometricEnabledState.value = false
                        },
                        onSuccess             = { id ->
                            isMounting = false
                            latestOnMountSuccess.value(id)
                        }
                    )
                } else {
                    val data = pendingEncryptState.value ?: return
                    latestOnSaveBio.value(cipher, data)
                    latestOnUnlock.value(data.password, data.pim, data.hash, data.protectHidden, data.protectHiddenPim, data.protectHiddenKeyfileData)
                    pendingEncryptState.value = null
                }
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (isDecryptModeState.value) {
                    bioModeState.value          = BioUiMode.Cancelled
                    biometricEnabledState.value = false
                } else {
                    pendingEncryptState.value?.let { data ->
                        latestOnUnlock.value(data.password, data.pim, data.hash, data.protectHidden, data.protectHiddenPim, data.protectHiddenKeyfileData)
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

    LaunchedEffect(mountId, biometricUnlockEnabled) {
        if (!biometricUnlockEnabled) return@LaunchedEffect
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
    val canUnlock = bioMode == BioUiMode.Form && (password.isNotEmpty() || keyfiles.isNotEmpty()) && !isLoading

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
                    actions = {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(4.dp))
                        } else {
                            IconButton(
                                enabled = canUnlock,
                                onClick = {
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                    val protectedPassword = if (protectHidden && hiddenPassword.isNotBlank()) hiddenPassword else null
                                    val protectedPim = if (protectHidden) (hiddenPimValue.toIntOrNull() ?: 0) else 0
                                    val protectedKeyfileData = if (protectHidden) hiddenKeyfiles.map { it.content } else emptyList()
                                    if (biometricEnabled && biometricAvailable) {
                                        val cryptoObj = viewModel.getBiometricCryptoObjectForEncrypt()
                                        if (cryptoObj != null) {
                                            isDecryptModeState.value  = false
                                            pendingEncryptState.value = EncryptPending(
                                                password                  = password,
                                                pim                       = pim,
                                                hash                      = selectedHash,
                                                protectHidden             = protectedPassword,
                                                protectHiddenPim          = protectedPim,
                                                protectHiddenKeyfileData  = protectedKeyfileData
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
                                            latestOnUnlock.value(password, pim, selectedHash, protectedPassword, protectedPim, protectedKeyfileData)
                                        }
                                    } else {
                                        latestOnUnlock.value(password, pim, selectedHash, protectedPassword, protectedPim, protectedKeyfileData)
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.Outlined.LockOpen,
                                    contentDescription = null,
                                    tint = if (canUnlock) MaterialTheme.colorScheme.primary
                                           else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                )
                            }
                        }
                    }
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
                Box(
                    modifier         = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.Lock,
                        contentDescription = null,
                        tint     = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))
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
                                    .onGloballyPositioned { passwordAutofillNode.boundingBox = it.boundsInRoot() }
                                    .onFocusChanged { focusState ->
                                        if (focusState.isFocused) {
                                            focusedField = "password"
                                            autofillScope.launch {
                                                delay(150)
                                                autofillManager?.cancel()
                                                autofill?.requestAutofillForNode(passwordAutofillNode)
                                            }
                                        } else {
                                            autofill?.cancelAutofillForNode(passwordAutofillNode)
                                        }
                                    }
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
                                visualTransformation = if (showPim) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon         = {
                                    IconButton(onClick = { showPim = !showPim }) {
                                        Icon(
                                            if (showPim) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                            contentDescription = null
                                        )
                                    }
                                },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.NumberPassword,
                                    imeAction    = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        if (canUnlock) latestOnUnlock.value(password, pim, selectedHash, if (protectHidden && hiddenPassword.isNotBlank()) hiddenPassword else null, if (protectHidden) (hiddenPimValue.toIntOrNull() ?: 0) else 0, if (protectHidden) hiddenKeyfiles.map { it.content } else emptyList())
                                    }
                                ),
                                singleLine = true,
                                modifier   = Modifier.fillMaxWidth()
                            )

                            Box(modifier = Modifier.fillMaxWidth()) {
                                OutlinedTextField(
                                    value         = hashes.first { it.first == selectedHash }.second,
                                    onValueChange = {},
                                    readOnly      = true,
                                    label         = { Text(stringResource(R.string.vault_mount_hash)) },
                                    trailingIcon  = {
                                        Icon(Icons.Outlined.ExpandMore, contentDescription = null)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Box(Modifier.matchParentSize().clickable { showHashSheet = true })
                            }

                            if (showHashSheet) {
                                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                                AppSheet(
                                    onDismissRequest = { showHashSheet = false },
                                    sheetState       = sheetState
                                ) {
                                    Column(modifier = Modifier.padding(bottom = 32.dp)) {
                                        Text(
                                            text       = stringResource(R.string.vault_mount_hash),
                                            style      = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier   = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                        )
                                        hashes.forEach { (id, label) ->
                                            Row(
                                                modifier          = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { selectedHash = id; showHashSheet = false }
                                                    .padding(horizontal = 4.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                RadioButton(
                                                    selected = selectedHash == id,
                                                    onClick  = { selectedHash = id; showHashSheet = false }
                                                )
                                                Text(
                                                    text     = label,
                                                    style    = MaterialTheme.typography.bodyMedium,
                                                    modifier = Modifier.padding(start = 8.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

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
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { protectHidden = !protectHidden }
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Outlined.HideSource,
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
                                            onCheckedChange = { protectHidden = it }
                                        )
                                    }
                                    AnimatedVisibility(visible = protectHidden) {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedTextField(
                                                value                = hiddenPassword,
                                                onValueChange        = { hiddenPassword = it },
                                                label                = { Text(stringResource(R.string.vault_mount_hidden_password)) },
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
                                                    .onGloballyPositioned { hiddenPasswordAutofillNode.boundingBox = it.boundsInRoot() }
                                                    .onFocusChanged { focusState ->
                                                        if (focusState.isFocused) {
                                                            focusedField = "hidden"
                                                            autofillScope.launch {
                                                                delay(150)
                                                                autofillManager?.cancel()
                                                                autofill?.requestAutofillForNode(hiddenPasswordAutofillNode)
                                                            }
                                                        } else {
                                                            autofill?.cancelAutofillForNode(hiddenPasswordAutofillNode)
                                                        }
                                                    }
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
                                                visualTransformation = if (showHiddenPim) VisualTransformation.None else PasswordVisualTransformation(),
                                                trailingIcon         = {
                                                    IconButton(onClick = { showHiddenPim = !showHiddenPim }) {
                                                        Icon(if (showHiddenPim) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = null)
                                                    }
                                                },
                                                keyboardOptions      = KeyboardOptions(
                                                    keyboardType = KeyboardType.NumberPassword,
                                                    imeAction    = ImeAction.Done
                                                ),
                                                singleLine = true,
                                                modifier   = Modifier.fillMaxWidth()
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

        // ── Mounting overlay ──────────────────────────────────────────────────
        if (isMounting) {
            Box(Modifier.fillMaxSize().zIndex(100f)) {
                MountingOverlay(
                    isError        = mountState is VaultViewModel.MountState.Error,
                    logs           = mountLogs,
                    onCancel       = { viewModel.cancelMount(); onBack() },
                    onDismissError = { viewModel.resetMountState(); isMounting = false }
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

        // ── Keyfile missing overlay ───────────────────────────────────────────
        AnimatedVisibility(
            visible  = biometricKeyfileMissing,
            enter    = fadeIn(animationSpec = tween(250)),
            exit     = fadeOut(animationSpec = tween(300)),
            modifier = Modifier.fillMaxSize().zIndex(99f)
        ) {
            val errorComposition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.error))
            val errorProgress    by animateLottieCompositionAsState(errorComposition, iterations = 1)
            BackHandler(enabled = biometricKeyfileMissing) { biometricKeyfileMissing = false; bioMode = BioUiMode.Form }
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
                        text       = stringResource(R.string.vault_biometric_keyfile_missing_title),
                        style      = MaterialTheme.typography.titleLarge,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        color      = androidx.compose.ui.graphics.Color.White,
                        textAlign  = TextAlign.Center
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text      = stringResource(R.string.vault_biometric_keyfile_missing_body),
                        style     = MaterialTheme.typography.bodyMedium,
                        color     = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center
                    )
                }
                androidx.compose.material3.Button(
                    onClick  = { biometricKeyfileMissing = false; bioMode = BioUiMode.Form },
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
