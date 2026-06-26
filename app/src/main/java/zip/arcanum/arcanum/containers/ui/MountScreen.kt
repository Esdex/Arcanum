package zip.arcanum.arcanum.containers.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
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
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Switch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import zip.arcanum.R
import zip.arcanum.core.components.AppDialog
import zip.arcanum.core.database.entities.ContainerEntity
import zip.arcanum.core.utils.FileUtils
import zip.arcanum.crypto.VeraCryptEngine
import javax.crypto.Cipher
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
    val protectHidden: String?,
    val protectHiddenPim: Int,
    val protectHiddenKeyfilePaths: List<String>
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
    val context    = LocalContext.current
    val mountState by viewModel.mountState.collectAsState()
    val mountLogs  by viewModel.mountLogs.collectAsState()
    val mountId    = container.id

    var keyfiles by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var hiddenKeyfiles by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    val keyfilePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val (path, name) = FileUtils.copyUriToCache(context, uri) ?: return@rememberLauncherForActivityResult
        keyfiles = keyfiles + Pair(path, name)
    }
    val hiddenKeyfilePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val (path, name) = FileUtils.copyUriToCache(context, uri) ?: return@rememberLauncherForActivityResult
        hiddenKeyfiles = hiddenKeyfiles + Pair(path, name)
    }

    var isMounting by remember { mutableStateOf(false) }

    // ── Form state ────────────────────────────────────────────────────────
    var password          by remember { mutableStateOf("") }
    var showPassword      by remember { mutableStateOf(false) }
    var algorithmExpanded by remember { mutableStateOf(false) }
    var hashExpanded      by remember { mutableStateOf(false) }
    var selectedAlgorithm by rememberSaveable { mutableIntStateOf(VeraCryptEngine.ALGO_AUTO) }
    var selectedHash      by rememberSaveable { mutableIntStateOf(VeraCryptEngine.HASH_AUTO) }
    var showAdvanced      by remember { mutableStateOf(false) }
    var pimValue          by remember { mutableStateOf("") }
    var showPim           by remember { mutableStateOf(false) }
    var protectHidden      by remember { mutableStateOf(false) }
    var hiddenPassword     by remember { mutableStateOf("") }
    var showHiddenPassword by remember { mutableStateOf(false) }
    var hiddenPimValue     by remember { mutableStateOf("") }
    var shakeKey          by remember { mutableIntStateOf(0) }
    val shakeAnim         = remember { Animatable(0f) }

    val algorithms = remember { listOf(-1 to "Auto") + (0..14).map { id -> id to VeraCryptEngine.algorithmIdToString(id).replace("-256-XTS", "") } }
    val hashes     = remember { listOf(-1 to "Auto") + (0..4).map { it to VeraCryptEngine.hashIdToString(it) } }

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

    val onUnlock: (String, Int, Int, Int, String?, Int, List<String>) -> Unit = { pw, pim, algo, hash, protectPw, protectPim, protectKeyfilePaths ->
        isMounting = true
        viewModel.mountContainer(
            container                  = container,
            password                   = pw,
            keyfilePaths               = keyfiles.map { it.first },
            pim                        = pim,
            algorithm                  = algo,
            hashAlgorithm              = hash,
            protectHiddenPassword      = protectPw,
            protectHiddenPim           = protectPim,
            protectHiddenKeyfilePaths  = protectKeyfilePaths,
            onSuccess = { id ->
                keyfiles.forEach { FileUtils.secureZeroAndDelete(java.io.File(it.first)) }
                hiddenKeyfiles.forEach { FileUtils.secureZeroAndDelete(java.io.File(it.first)) }
                keyfiles       = emptyList()
                hiddenKeyfiles = emptyList()
                isMounting     = false
                onMountSuccess(id)
            }
        )
    }
    val latestOnUnlock    = rememberUpdatedState(onUnlock)
    val latestOnSaveBio   = rememberUpdatedState<(Cipher, String, Int) -> Unit> { cipher, pw, pim ->
        viewModel.saveBiometricCredentials(mountId, cipher, pw, pim)
    }
    val latestOnDecryptBio = rememberUpdatedState<(Cipher) -> Pair<String, Int>?> { cipher ->
        viewModel.decryptBiometricCredentials(mountId, cipher)
    }

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
                    latestOnUnlock.value(creds.first, creds.second, VeraCryptEngine.ALGO_AUTO, VeraCryptEngine.HASH_AUTO, null, 0, emptyList())
                } else {
                    val data = pendingEncryptState.value ?: return
                    latestOnSaveBio.value(cipher, data.password, data.pim)
                    latestOnUnlock.value(data.password, data.pim, data.algorithm, data.hash, data.protectHidden, data.protectHiddenPim, data.protectHiddenKeyfilePaths)
                    pendingEncryptState.value = null
                }
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (isDecryptModeState.value) {
                    bioModeState.value          = BioUiMode.Cancelled
                    biometricEnabledState.value = false
                } else {
                    pendingEncryptState.value?.let { data ->
                        latestOnUnlock.value(data.password, data.pim, data.algorithm, data.hash, data.protectHidden, data.protectHiddenPim, data.protectHiddenKeyfilePaths)
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
    val canUnlock = bioMode == BioUiMode.Form && (password.isNotEmpty() || keyfiles.isNotEmpty()) && !isLoading

    BackHandler(enabled = !isMounting) {
        keyfiles.forEach { FileUtils.secureZeroAndDelete(java.io.File(it.first)) }
        hiddenKeyfiles.forEach { FileUtils.secureZeroAndDelete(java.io.File(it.first)) }
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
                            keyfiles.forEach { FileUtils.secureZeroAndDelete(java.io.File(it.first)) }
                            hiddenKeyfiles.forEach { FileUtils.secureZeroAndDelete(java.io.File(it.first)) }
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
                                    val protectedPassword = if (protectHidden && hiddenPassword.isNotBlank()) hiddenPassword else null
                                    val protectedPim = if (protectHidden) (hiddenPimValue.toIntOrNull() ?: 0) else 0
                                    val protectedKeyfilePaths = if (protectHidden) hiddenKeyfiles.map { it.first } else emptyList()
                                    if (biometricEnabled && !localHasBiometricSaved) {
                                        val cryptoObj = viewModel.getBiometricCryptoObjectForEncrypt()
                                        if (cryptoObj != null) {
                                            isDecryptModeState.value  = false
                                            pendingEncryptState.value = EncryptPending(
                                                password                   = password,
                                                pim                        = pim,
                                                algorithm                  = selectedAlgorithm,
                                                hash                       = selectedHash,
                                                protectHidden              = protectedPassword,
                                                protectHiddenPim           = protectedPim,
                                                protectHiddenKeyfilePaths  = protectedKeyfilePaths
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
                                            latestOnUnlock.value(password, pim, selectedAlgorithm, selectedHash, protectedPassword, protectedPim, protectedKeyfilePaths)
                                        }
                                    } else {
                                        latestOnUnlock.value(password, pim, selectedAlgorithm, selectedHash, protectedPassword, protectedPim, protectedKeyfilePaths)
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
                        Icons.Outlined.Storage,
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
                val displayPath = remember(container.path, container.safUri) {
                    when {
                        container.path.isNotBlank() -> container.path
                        container.safUri.isNotBlank() -> {
                            val seg = android.net.Uri.decode(
                                android.net.Uri.parse(container.safUri).lastPathSegment ?: ""
                            )
                            val after = seg.substringAfter(':')
                            if (after.isNotEmpty()) "/$after" else seg
                        }
                        else -> ""
                    }
                }
                if (displayPath.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text      = displayPath,
                        style     = MaterialTheme.typography.bodySmall,
                        color     = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
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
                            )

                            OutlinedTextField(
                                value         = pimValue,
                                onValueChange = {
                                    if (it.all { c -> c.isDigit() } && it.length <= 4) pimValue = it
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
                                        if (canUnlock) latestOnUnlock.value(password, pim, selectedAlgorithm, selectedHash, if (protectHidden && hiddenPassword.isNotBlank()) hiddenPassword else null, if (protectHidden) (hiddenPimValue.toIntOrNull() ?: 0) else 0, if (protectHidden) hiddenKeyfiles.map { it.first } else emptyList())
                                    }
                                ),
                                singleLine = true,
                                modifier   = Modifier.fillMaxWidth()
                            )

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

                            keyfiles.forEachIndexed { index, (_, displayName) ->
                                Row(
                                    modifier          = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(displayName, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                    IconButton(
                                        onClick  = {
                                            val updated = keyfiles.toMutableList()
                                            FileUtils.secureZeroAndDelete(java.io.File(updated[index].first))
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
                                onClick  = { keyfilePickerLauncher.launch("*/*") },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Outlined.Lock, null, modifier = Modifier.size(16.dp))
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
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            OutlinedTextField(
                                                value         = hiddenPimValue,
                                                onValueChange = {
                                                    if (it.all { c -> c.isDigit() } && it.length <= 4) hiddenPimValue = it
                                                },
                                                label                = { Text(stringResource(R.string.vault_mount_pim_label)) },
                                                placeholder          = { Text(stringResource(R.string.vault_mount_pim_placeholder)) },
                                                visualTransformation = PasswordVisualTransformation(),
                                                keyboardOptions      = KeyboardOptions(
                                                    keyboardType = KeyboardType.NumberPassword,
                                                    imeAction    = ImeAction.Done
                                                ),
                                                singleLine = true,
                                                modifier   = Modifier.fillMaxWidth()
                                            )
                                            hiddenKeyfiles.forEachIndexed { index, (_, displayName) ->
                                                Row(
                                                    modifier          = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                                    Spacer(Modifier.width(6.dp))
                                                    Text(displayName, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                                    IconButton(
                                                        onClick  = {
                                                            val updated = hiddenKeyfiles.toMutableList()
                                                            FileUtils.secureZeroAndDelete(java.io.File(updated[index].first))
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
                                                onClick  = { hiddenKeyfilePickerLauncher.launch("*/*") },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Icon(Icons.Outlined.Lock, null, modifier = Modifier.size(16.dp))
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
    }
}
