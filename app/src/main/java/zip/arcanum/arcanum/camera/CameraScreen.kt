package zip.arcanum.arcanum.camera

import android.view.OrientationEventListener
import android.view.Surface as ViewSurface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.FlashAuto
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import zip.arcanum.R
import zip.arcanum.core.components.AppDialog
import zip.arcanum.core.components.BackButton
import java.io.ByteArrayOutputStream

/**
 * The camera, for photos that go straight into the folder it was opened from (#145).
 *
 * It stays open after a shot so the next one is a tap away; the last photo kept shows in the
 * corner. Bound to this screen's lifecycle, so the camera is released the moment the screen
 * is left or the app goes to the background, and the system's camera indicator goes with it.
 */
@Composable
fun CameraScreen(
    onBack: () -> Unit,
    viewModel: CameraViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsState()

    var front by rememberSaveable { mutableStateOf(false) }
    var flash by rememberSaveable { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    var hasFront by remember { mutableStateOf(false) }
    var hasBack by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    val shutterFlash = remember { Animatable(0f) }

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val preview = remember { Preview.Builder().build() }
    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }

    LaunchedEffect(flash) { imageCapture.flashMode = flash }

    LaunchedEffect(front) {
        val provider = runCatching { ProcessCameraProvider.awaitInstance(context) }.getOrElse { failed = true; return@LaunchedEffect }
        hasFront = runCatching { provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false)
        hasBack = runCatching { provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false)
        val selector = when {
            front && hasFront -> CameraSelector.DEFAULT_FRONT_CAMERA
            hasBack -> CameraSelector.DEFAULT_BACK_CAMERA
            hasFront -> CameraSelector.DEFAULT_FRONT_CAMERA
            else -> { failed = true; return@LaunchedEffect }
        }
        failed = runCatching {
            provider.unbindAll()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
        }.isFailure
    }

    // Which way up the phone is held, not which way the screen is turned: with auto-rotate
    // off the screen stays upright, and a photo taken sideways would be saved on its side.
    DisposableEffect(imageCapture) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                imageCapture.targetRotation = when (degrees) {
                    in 45 until 135  -> ViewSurface.ROTATION_270
                    in 135 until 225 -> ViewSurface.ROTATION_180
                    in 225 until 315 -> ViewSurface.ROTATION_90
                    else             -> ViewSurface.ROTATION_0
                }
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }

    LaunchedEffect(state.vaultClosed) { if (state.vaultClosed) onBack() }

    fun shoot() {
        if (capturing || failed) return
        capturing = true
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            shutterFlash.snapTo(0.6f)
            shutterFlash.animateTo(0f, tween(220))
        }
        // The JPEG is made into memory; the view model writes it into the vault.
        val out = ByteArrayOutputStream()
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(out).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    capturing = false
                    viewModel.save(out.toByteArray())
                }
                override fun onError(exception: ImageCaptureException) {
                    capturing = false
                    viewModel.captureFailed()
                }
            }
        )
    }

    if (state.saveFailed) {
        AppDialog(
            onDismissRequest = viewModel::dismissSaveFailed,
            title = { Text(stringResource(R.string.camera_not_saved_title)) },
            text  = { Text(stringResource(R.string.camera_not_saved_message)) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissSaveFailed) { Text(stringResource(R.string.common_ok)) }
            }
        )
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = shutterFlash.value)))

            if (failed) {
                Text(
                    stringResource(R.string.camera_failed_to_start),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BackButton(onClick = onBack, overMedia = true)
                OverlayButton(
                    onClick = {
                        flash = when (flash) {
                            ImageCapture.FLASH_MODE_OFF  -> ImageCapture.FLASH_MODE_AUTO
                            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                            else                         -> ImageCapture.FLASH_MODE_OFF
                        }
                    }
                ) {
                    when (flash) {
                        ImageCapture.FLASH_MODE_AUTO -> Icon(Icons.Outlined.FlashAuto, stringResource(R.string.camera_flash_auto))
                        ImageCapture.FLASH_MODE_ON   -> Icon(Icons.Outlined.FlashOn, stringResource(R.string.camera_flash_on))
                        else                         -> Icon(Icons.Outlined.FlashOff, stringResource(R.string.camera_flash_off))
                    }
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 32.dp, vertical = 32.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // The last photo kept, so it is plain that it went somewhere.
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.15f))
                ) {
                    state.lastShot?.let {
                        Image(
                            it.asImageBitmap(),
                            contentDescription = stringResource(R.string.camera_last_photo),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                val shutterLabel = stringResource(R.string.camera_take_photo)
                Box(
                    Modifier
                        .size(76.dp)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(8.dp)
                        .clip(CircleShape)
                        .background(if (capturing) Color.White.copy(alpha = 0.5f) else Color.White)
                        .clickable(enabled = !failed, onClickLabel = shutterLabel, role = Role.Button) { shoot() }
                )

                if (hasFront && hasBack) {
                    OverlayButton(onClick = { front = !front }, size = 56) {
                        Icon(Icons.Outlined.Cameraswitch, stringResource(R.string.camera_switch))
                    }
                } else {
                    Spacer(Modifier.size(56.dp))
                }
            }
        }
    }
}

/** A round button that reads over a moving picture, like the back button over media. */
@Composable
private fun OverlayButton(onClick: () -> Unit, size: Int = 40, content: @Composable () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(size.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = Color.Black.copy(alpha = 0.35f),
            contentColor   = Color.White
        )
    ) { content() }
}
