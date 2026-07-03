package zip.arcanum.setup

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.IntentFilter
import android.content.Context
import android.graphics.ImageFormat
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaRecorder
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.io.File
import zip.arcanum.R
import zip.arcanum.core.security.DisguiseProfile

private const val DISGUISE_UNLOCK_HOLD_MS = 7_000L

private enum class SystemInfoTab {
    OVERVIEW,
    SYSTEM,
    CPU,
    DISPLAY,
    BATTERY,
    ANDROID,
    DEVICES,
    THERMAL,
    SENSORS
}

@Composable
fun DisguiseUnlockScreen(
    profile: DisguiseProfile,
    onUnlockRequested: () -> Unit
) {
    when (profile) {
        DisguiseProfile.TIMER -> TimerDisguiseScreen(profile, onUnlockRequested)
        DisguiseProfile.STOPWATCH -> StopwatchDisguiseScreen(profile, onUnlockRequested)
        else -> DashboardDisguiseScreen(profile, onUnlockRequested)
    }
}

@Composable
private fun DashboardDisguiseScreen(
    profile: DisguiseProfile,
    onUnlockRequested: () -> Unit
) {
    val effectiveProfile = DisguiseProfile.canonical(profile)
    val color = fakeProfileColor(effectiveProfile)
    val context = LocalContext.current
    val cameraManager = remember(context) { context.getSystemService(CameraManager::class.java) }
    val torchCameraId = remember(context) { findTorchCameraId(context) }
    var taskOptimized by remember(effectiveProfile) { mutableStateOf(false) }
    var systemChecked by remember(effectiveProfile) { mutableStateOf(false) }
    var diagnosticsRun by remember(effectiveProfile) { mutableStateOf(false) }
    var noticeRes by remember(effectiveProfile) { mutableStateOf<Int?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val isFlashlight = effectiveProfile == DisguiseProfile.FLASHLIGHT
    val flashlightAvailable = torchCameraId != null
    DisposableEffect(torchCameraId) {
        onDispose {
            if (torchCameraId != null) runCatching { cameraManager.setTorchMode(torchCameraId, false) }
        }
    }

    fun toggleFlashlight(): Boolean {
        val cameraId = torchCameraId ?: return false
        if (!isFlashlight) return false
        if (!hasCameraPermission) {
            return false
        }
        return runCatching {
            cameraManager.setTorchMode(cameraId, !torchOn)
            torchOn = !torchOn
        }.isSuccess
    }

    fun handleActionClick() {
        when (effectiveProfile) {
            DisguiseProfile.FLASHLIGHT -> toggleFlashlight()
            else -> Unit
        }
    }

    fun runDiagnostics() {
        diagnosticsRun = true
        noticeRes = R.string.fake_tools_diagnostics_notice
    }

    fun optimizeSystem() {
        taskOptimized = true
        noticeRes = R.string.fake_task_manager_optimized_notice
    }

    fun checkUpdates() {
        systemChecked = true
        noticeRes = R.string.fake_system_no_updates_notice
    }

    val statusText = when {
        isFlashlight -> stringResource(
            when {
                !flashlightAvailable -> R.string.fake_flashlight_unavailable
                torchOn -> R.string.fake_flashlight_status_on
                else -> R.string.fake_flashlight_status
            }
        )
        taskOptimized -> stringResource(R.string.fake_system_status_ready)
        systemChecked -> stringResource(R.string.fake_system_status_updated)
        diagnosticsRun -> stringResource(R.string.fake_system_status_ready)
        else -> stringResource(fakeStatusRes(effectiveProfile))
    }
    val actionText = if (isFlashlight) {
        stringResource(
            when {
                !flashlightAvailable -> R.string.fake_flashlight_action_unavailable
                !hasCameraPermission -> R.string.fake_flashlight_action_unavailable
                torchOn -> R.string.fake_flashlight_action_off
                else -> R.string.fake_flashlight_action
            }
        )
    } else {
        stringResource(R.string.fake_system_action_diagnostics)
    }

    FakeAppShell(profile = effectiveProfile, color = color, onUnlockRequested = onUnlockRequested) {
        if (isFlashlight) {
            FlashlightDashboard(
                profile = effectiveProfile,
                color = color,
                statusText = statusText,
                torchOn = torchOn,
                hasCameraPermission = hasCameraPermission,
                flashlightAvailable = flashlightAvailable,
                actionText = actionText,
                onActionClick = { handleActionClick() },
                noticeRes = noticeRes
            )
        } else {
            SystemDiagnosticsDashboard(
                profile = effectiveProfile,
                color = color,
                context = context,
                statusText = statusText,
                taskOptimized = taskOptimized,
                systemChecked = systemChecked,
                diagnosticsRun = diagnosticsRun,
                onRunDiagnostics = { runDiagnostics() },
                onOptimize = { optimizeSystem() },
                onCheckUpdates = { checkUpdates() },
                noticeRes = noticeRes
            )
        }
    }
}

@Composable
private fun SystemDiagnosticsDashboard(
    profile: DisguiseProfile,
    color: Color,
    context: Context,
    statusText: String,
    taskOptimized: Boolean,
    systemChecked: Boolean,
    diagnosticsRun: Boolean,
    onRunDiagnostics: () -> Unit,
    onOptimize: () -> Unit,
    onCheckUpdates: () -> Unit,
    noticeRes: Int?
) {
    var liveTick by remember { mutableStateOf(0) }
    var diagnosticsBusy by remember { mutableStateOf(false) }
    var optimizeBusy by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(SystemInfoTab.OVERVIEW) }
    val sensorManager = remember(context) { context.getSystemService(SensorManager::class.java) }
    val availableSensors = remember(sensorManager) {
        sensorManager?.getSensorList(Sensor.TYPE_ALL).orEmpty()
    }
    val sensorsActive = selectedTab == SystemInfoTab.SENSORS
    val accelerometer = rememberSensorReading(Sensor.TYPE_ACCELEROMETER, enabled = sensorsActive)
    val gyroscope = rememberSensorReading(Sensor.TYPE_GYROSCOPE, enabled = sensorsActive)
    val magnetic = rememberSensorReading(Sensor.TYPE_MAGNETIC_FIELD, enabled = sensorsActive)
    val light = rememberSensorReading(Sensor.TYPE_LIGHT, enabled = sensorsActive)
    val proximity = rememberSensorReading(Sensor.TYPE_PROXIMITY, enabled = sensorsActive)
    val pressure = rememberSensorReading(Sensor.TYPE_PRESSURE, enabled = sensorsActive)
    val heavyRefreshTick = liveTick / 5
    val snapshot = remember(context, heavyRefreshTick, taskOptimized) {
        readLiveSystemSnapshot(context, heavyRefreshTick, taskOptimized)
    }
    val liveCoreLoads = remember(liveTick, taskOptimized, snapshot.coreLoads.size) {
        fakeCoreLoads(snapshot.coreLoads.size, liveTick, taskOptimized)
    }
    val live = remember(snapshot, liveCoreLoads) {
        snapshot.copy(
            coreLoads = liveCoreLoads,
            cpuAverage = liveCoreLoads.average().toInt().coerceIn(0, 100)
        )
    }
    val score = (100 - live.cpuAverage / 4 - live.memoryUsedPercent / 8).coerceIn(82, 99)
    val temperatureText = live.cpuTemperatureC?.let { formatTemperature(it) }
        ?: live.batteryTemperatureC?.let { formatTemperature(it) }
        ?: stringResource(R.string.fake_system_unknown)

    LaunchedEffect(selectedTab) {
        while (true) {
            val refreshMs = when (selectedTab) {
                SystemInfoTab.OVERVIEW,
                SystemInfoTab.CPU,
                SystemInfoTab.SENSORS -> 1_000L
                SystemInfoTab.BATTERY,
                SystemInfoTab.THERMAL -> 3_000L
                else -> 8_000L
            }
            delay(refreshMs)
            liveTick += 1
        }
    }

    LaunchedEffect(diagnosticsBusy) {
        if (diagnosticsBusy) {
            delay(1_450L)
            diagnosticsBusy = false
            onRunDiagnostics()
        }
    }

    LaunchedEffect(optimizeBusy) {
        if (optimizeBusy) {
            delay(1_700L)
            optimizeBusy = false
            onOptimize()
        }
    }

    FakeHeroCard(
        profile = profile,
        color = color,
        statusText = statusText,
        secondaryText = stringResource(R.string.fake_system_secondary)
    )

    SystemInfoTabs(selected = selectedTab, onSelected = { selectedTab = it })

    when (selectedTab) {
        SystemInfoTab.OVERVIEW -> {
            FakeHealthScorePanel(
                color = color,
                score = score,
                title = stringResource(R.string.fake_system_health_score),
                subtitle = stringResource(R.string.fake_system_health_subtitle),
                values = live.coreLoads
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FakeMetricCard(label = "CPU", value = "${live.cpuAverage}%", modifier = Modifier.weight(1f))
                FakeMetricCard(label = "RAM", value = "${live.memoryUsedPercent}%", modifier = Modifier.weight(1f))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FakeMetricCard(label = stringResource(R.string.fake_system_temperature), value = temperatureText, modifier = Modifier.weight(1f))
                FakeMetricCard(label = stringResource(R.string.fake_system_sensors), value = availableSensors.size.toString(), modifier = Modifier.weight(1f))
            }
            FakeSectionCard(
                title = stringResource(R.string.fake_system_resources),
                rows = fakeSystemResourceRows(live)
            )
            FakeSystemActions(
                diagnosticsBusy = diagnosticsBusy,
                optimizeBusy = optimizeBusy,
                onDiagnostics = { diagnosticsBusy = true },
                onOptimize = { optimizeBusy = true },
                onCheckUpdates = onCheckUpdates
            )
            FakeDisguiseNotice(noticeRes = noticeRes)
        }
        SystemInfoTab.SYSTEM -> {
            FakeSectionCard(title = stringResource(R.string.fake_aida_section_system), rows = fakeDeviceIdentityRows(context, live))
            FakeSectionCard(title = stringResource(R.string.fake_system_platform_title), rows = fakeSystemPlatformRows(context, live), compact = true)
        }
        SystemInfoTab.CPU -> {
            FakeCoreLoadPanel(
                color = color,
                title = stringResource(R.string.fake_system_core_load),
                subtitle = stringResource(R.string.fake_system_live_refresh),
                loads = live.coreLoads
            )
            FakeSectionCard(title = stringResource(R.string.fake_aida_section_cpu), rows = fakeCpuRows(live), compact = true)
        }
        SystemInfoTab.DISPLAY -> {
            FakeSectionCard(title = stringResource(R.string.fake_aida_section_display), rows = fakeDisplayRows(context), compact = true)
        }
        SystemInfoTab.BATTERY -> {
            FakeSectionCard(title = stringResource(R.string.fake_aida_section_battery), rows = fakeBatteryRows(live), compact = true)
        }
        SystemInfoTab.ANDROID -> {
            FakeSectionCard(title = stringResource(R.string.fake_aida_section_android), rows = fakeAndroidRows(), compact = true)
        }
        SystemInfoTab.DEVICES -> {
            fakeCameraSections(context).forEach { section ->
                FakeSectionCard(title = section.first, rows = section.second, compact = true)
            }
            FakeSectionCard(title = stringResource(R.string.fake_system_capabilities_title), rows = fakeSystemCapabilityRows(context), compact = true)
        }
        SystemInfoTab.THERMAL -> {
            FakeSectionCard(title = stringResource(R.string.fake_aida_section_thermal), rows = fakeThermalRows(live), compact = true)
        }
        SystemInfoTab.SENSORS -> {
            FakeSectionCard(
                title = stringResource(R.string.fake_system_sensors_title),
                rows = fakeSensorRows(
                    accelerometer = accelerometer,
                    gyroscope = gyroscope,
                    magnetic = magnetic,
                    light = light,
                    proximity = proximity,
                    pressure = pressure,
                    sensorCount = availableSensors.size
                ),
                compact = true
            )
        }
    }
}

@Composable
private fun SystemInfoTabs(
    selected: SystemInfoTab,
    onSelected: (SystemInfoTab) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SystemInfoTab.entries.forEach { tab ->
            val title = systemInfoTabTitle(tab)
            if (tab == selected) {
                Button(
                    onClick = { onSelected(tab) },
                    modifier = Modifier.height(42.dp),
                    shape = RoundedCornerShape(99.dp)
                ) {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                OutlinedButton(
                    onClick = { onSelected(tab) },
                    modifier = Modifier.height(42.dp),
                    shape = RoundedCornerShape(99.dp)
                ) {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun systemInfoTabTitle(tab: SystemInfoTab): String = when (tab) {
    SystemInfoTab.OVERVIEW -> stringResource(R.string.fake_system_tab_overview)
    SystemInfoTab.SYSTEM -> stringResource(R.string.fake_aida_section_system)
    SystemInfoTab.CPU -> stringResource(R.string.fake_aida_section_cpu)
    SystemInfoTab.DISPLAY -> stringResource(R.string.fake_aida_section_display)
    SystemInfoTab.BATTERY -> stringResource(R.string.fake_aida_section_battery)
    SystemInfoTab.ANDROID -> stringResource(R.string.fake_aida_section_android)
    SystemInfoTab.DEVICES -> stringResource(R.string.fake_aida_devices)
    SystemInfoTab.THERMAL -> stringResource(R.string.fake_aida_section_thermal)
    SystemInfoTab.SENSORS -> stringResource(R.string.fake_system_sensors)
}

@Composable
private fun FakeSystemActions(
    diagnosticsBusy: Boolean,
    optimizeBusy: Boolean,
    onDiagnostics: () -> Unit,
    onOptimize: () -> Unit,
    onCheckUpdates: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = onDiagnostics,
            enabled = !diagnosticsBusy && !optimizeBusy,
            modifier = Modifier.weight(1f).height(52.dp),
            shape = RoundedCornerShape(99.dp)
        ) {
            FakeBusyButtonContent(
                busy = diagnosticsBusy,
                text = stringResource(R.string.fake_system_action_diagnostics),
                busyText = stringResource(R.string.fake_system_action_scanning)
            )
        }
        Button(
            onClick = onOptimize,
            enabled = !diagnosticsBusy && !optimizeBusy,
            modifier = Modifier.weight(1f).height(52.dp),
            shape = RoundedCornerShape(99.dp)
        ) {
            FakeBusyButtonContent(
                busy = optimizeBusy,
                text = stringResource(R.string.fake_task_manager_action),
                busyText = stringResource(R.string.fake_system_action_optimizing)
            )
        }
    }

    OutlinedButton(
        onClick = onCheckUpdates,
        modifier = Modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(99.dp)
    ) {
        Text(stringResource(R.string.fake_system_action), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FlashlightDashboard(
    profile: DisguiseProfile,
    color: Color,
    statusText: String,
    torchOn: Boolean,
    hasCameraPermission: Boolean,
    flashlightAvailable: Boolean,
    actionText: String,
    onActionClick: () -> Unit,
    noticeRes: Int?
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.fake_flashlight_secondary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(174.dp)) {
                CircularProgressIndicator(
                    progress = { if (torchOn) 1f else 0.08f },
                    modifier = Modifier.fillMaxSize(),
                    color = if (torchOn) Color(0xFFFFD54F) else color,
                    trackColor = color.copy(alpha = 0.13f),
                    strokeWidth = 12.dp
                )
                Box(
                    modifier = Modifier
                        .size(118.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = if (torchOn) {
                                    listOf(Color(0xFFFFF59D), Color(0xFFFFB300))
                                } else {
                                    listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0.08f))
                                }
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(profile.previewIconRes),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(68.dp)
                    )
                }
            }
            FakeInlineRows(
                rows = listOf(
                    stringResource(R.string.fake_torch) to when {
                        !flashlightAvailable -> stringResource(R.string.fake_torch_unavailable)
                        torchOn -> stringResource(R.string.fake_torch_on)
                        else -> stringResource(R.string.fake_torch_off)
                    },
                    stringResource(R.string.fake_permission) to if (hasCameraPermission) stringResource(R.string.fake_permission_granted) else stringResource(R.string.fake_permission_required),
                    stringResource(R.string.fake_brightness) to if (torchOn) "100%" else stringResource(R.string.fake_standby),
                    stringResource(R.string.fake_auto_timeout) to stringResource(R.string.fake_torch_off)
                )
            )
        }
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FakeMetricCard(label = stringResource(R.string.fake_metric_battery), value = "86%", modifier = Modifier.weight(1f))
        FakeMetricCard(label = "LED", value = if (flashlightAvailable) stringResource(R.string.fake_led_ready) else stringResource(R.string.fake_led_missing), modifier = Modifier.weight(1f))
    }

    Button(
        onClick = onActionClick,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(99.dp)
    ) {
        Text(actionText, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }

    FakeDisguiseNotice(noticeRes = noticeRes)
}

@Composable
private fun TimerDisguiseScreen(
    profile: DisguiseProfile,
    onUnlockRequested: () -> Unit
) {
    val color = fakeProfileColor(profile)
    var hoursText by remember(profile) { mutableStateOf("0") }
    var minutesText by remember(profile) { mutableStateOf("5") }
    var secondsText by remember(profile) { mutableStateOf("0") }
    var remainingSeconds by remember(profile) { mutableStateOf(5 * 60) }
    var running by remember(profile) { mutableStateOf(false) }
    var ringing by remember(profile) { mutableStateOf(false) }
    var noticeRes by remember(profile) { mutableStateOf<Int?>(null) }
    val context = LocalContext.current

    fun selectedSeconds(): Int = (
        hoursText.toIntOrNull().coerceBounded(0, 23) * 3600 +
            minutesText.toIntOrNull().coerceBounded(0, 59) * 60 +
            secondsText.toIntOrNull().coerceBounded(0, 59)
        ).coerceAtLeast(0)

    fun syncRemainingIfStopped() {
        if (!running && !ringing) remainingSeconds = selectedSeconds()
    }

    LaunchedEffect(running) {
        while (running) {
            delay(1_000L)
            if (remainingSeconds > 1) {
                remainingSeconds -= 1
            } else {
                remainingSeconds = 0
                running = false
                ringing = true
                noticeRes = R.string.fake_timer_finished_notice
            }
        }
    }

    DisposableEffect(ringing) {
        var ringtone: Ringtone? = null
        if (ringing) {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ringtone = RingtoneManager.getRingtone(context, uri)?.also { tone ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) tone.isLooping = true
                runCatching { tone.play() }
            }
        }
        onDispose { runCatching { ringtone?.stop() } }
    }

    val selected = selectedSeconds()
    val progress = if (selected <= 0) 0f else (remainingSeconds.toFloat() / selected.toFloat()).coerceIn(0f, 1f)

    FakeAppShell(profile = profile, color = color, onUnlockRequested = onUnlockRequested) {
        FakeHeroCard(
            profile = profile,
            color = color,
            statusText = formatTimer(remainingSeconds),
            secondaryText = stringResource(if (ringing) R.string.fake_timer_ringing_secondary else R.string.fake_timer_secondary)
        )

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = stringResource(R.string.fake_timer_duration),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    TimerInputField(
                        label = stringResource(R.string.fake_timer_hours),
                        value = hoursText,
                        max = 23,
                        enabled = !running && !ringing,
                        onValueChange = { hoursText = it; syncRemainingIfStopped() },
                        modifier = Modifier.weight(1f)
                    )
                    TimerInputField(
                        label = stringResource(R.string.fake_timer_minutes),
                        value = minutesText,
                        max = 59,
                        enabled = !running && !ringing,
                        onValueChange = { minutesText = it; syncRemainingIfStopped() },
                        modifier = Modifier.weight(1f)
                    )
                    TimerInputField(
                        label = stringResource(R.string.fake_timer_seconds),
                        value = secondsText,
                        max = 59,
                        enabled = !running && !ringing,
                        onValueChange = { secondsText = it; syncRemainingIfStopped() },
                        modifier = Modifier.weight(1f)
                    )
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(99.dp)),
                    color = color
                )
                FakeInlineRows(
                    rows = listOf(
                        stringResource(R.string.fake_timer_alert) to stringResource(R.string.fake_timer_alert_default)
                    )
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    if (ringing) {
                        ringing = false
                        noticeRes = null
                    } else {
                        val target = selectedSeconds()
                        if (!running && remainingSeconds == 0) remainingSeconds = target
                        if (!running && remainingSeconds <= 0 && target > 0) remainingSeconds = target
                        running = !running && (remainingSeconds > 0 || target > 0)
                        noticeRes = if (running) R.string.fake_timer_started_notice else R.string.fake_timer_paused_notice
                    }
                },
                enabled = ringing || selected > 0 || remainingSeconds > 0,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(99.dp)
            ) {
                Text(stringResource(
                    when {
                        ringing -> R.string.fake_timer_action_stop
                        running -> R.string.fake_timer_action_pause
                        else -> R.string.fake_timer_action
                    }
                ))
            }
            OutlinedButton(
                onClick = {
                    running = false
                    ringing = false
                    remainingSeconds = selectedSeconds()
                    noticeRes = null
                },
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(99.dp)
            ) {
                Text(stringResource(R.string.fake_timer_action_reset))
            }
        }

        FakeDisguiseNotice(noticeRes = noticeRes)
    }
}

@Composable
private fun StopwatchDisguiseScreen(
    profile: DisguiseProfile,
    onUnlockRequested: () -> Unit
) {
    val color = fakeProfileColor(profile)
    var elapsedSeconds by remember(profile) { mutableStateOf(0) }
    var running by remember(profile) { mutableStateOf(false) }
    var laps by remember(profile) { mutableStateOf(emptyList<Int>()) }
    var lastLapAt by remember(profile) { mutableStateOf(0) }
    var noticeRes by remember(profile) { mutableStateOf<Int?>(null) }

    LaunchedEffect(running) {
        while (running) {
            delay(1_000L)
            elapsedSeconds += 1
        }
    }

    FakeAppShell(profile = profile, color = color, onUnlockRequested = onUnlockRequested) {
        FakeHeroCard(
            profile = profile,
            color = color,
            statusText = formatStopwatch(elapsedSeconds),
            secondaryText = stringResource(R.string.fake_stopwatch_secondary)
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    running = !running
                    noticeRes = if (running) R.string.fake_stopwatch_started_notice else R.string.fake_stopwatch_paused_notice
                },
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(99.dp)
            ) {
                Text(stringResource(if (running) R.string.fake_stopwatch_action_pause else R.string.fake_stopwatch_action))
            }
            OutlinedButton(
                onClick = {
                    val split = elapsedSeconds - lastLapAt
                    if (split > 0) {
                        laps = listOf(split) + laps
                        lastLapAt = elapsedSeconds
                    }
                },
                enabled = elapsedSeconds > 0,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(99.dp)
            ) {
                Text(stringResource(R.string.fake_stopwatch_action_lap))
            }
        }

        OutlinedButton(
            onClick = {
                running = false
                elapsedSeconds = 0
                laps = emptyList()
                lastLapAt = 0
                noticeRes = null
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(99.dp)
        ) {
            Text(stringResource(R.string.fake_stopwatch_action_reset))
        }

        FakeDisguiseNotice(noticeRes = noticeRes)
    }
}

@Composable
private fun FakeHealthScorePanel(
    color: Color,
    score: Int,
    title: String,
    subtitle: String,
    values: List<Int>
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(color.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = score.toString(),
                        style = MaterialTheme.typography.titleLarge,
                        color = color,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            FakeBarGraph(values = values, color = color)
        }
    }
}

@Composable
private fun FakeBarGraph(values: List<Int>, color: Color) {
    Canvas(modifier = Modifier.fillMaxWidth().height(86.dp)) {
        val safeValues = values.ifEmpty { listOf(1) }
        val gap = 8.dp.toPx()
        val barWidth = ((size.width - gap * (safeValues.size - 1)) / safeValues.size).coerceAtLeast(4f)
        safeValues.forEachIndexed { index, raw ->
            val normalized = (raw.coerceIn(0, 100) / 100f)
            val barHeight = (size.height * (0.18f + normalized * 0.76f)).coerceAtLeast(8f)
            val left = index * (barWidth + gap)
            drawRoundRect(
                color = color.copy(alpha = 0.10f),
                topLeft = Offset(left, 0f),
                size = Size(barWidth, size.height),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.42f))
                ),
                topLeft = Offset(left, size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

@Composable
private fun FakeCoreLoadPanel(
    color: Color,
    title: String,
    subtitle: String,
    loads: List<Int>
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            FakeBarGraph(values = loads, color = color)
            FakeInlineRows(
                rows = loads.mapIndexed { index, load ->
                    stringResource(R.string.fake_system_core_number, index + 1) to "$load%"
                }
            )
        }
    }
}

@Composable
private fun FakeAppShell(
    profile: DisguiseProfile,
    color: Color,
    onUnlockRequested: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            color.copy(alpha = 0.18f),
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            FakeDisguiseHeader(profile = profile, color = color, onUnlockRequested = onUnlockRequested)
            content()
            Spacer(Modifier.height(8.dp))
        }
        }
    }
}

@Composable
private fun FakeDisguiseHeader(
    profile: DisguiseProfile,
    color: Color,
    onUnlockRequested: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(color),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(profile.previewIconRes),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(32.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(profile.labelRes),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.disguiseUnlockHold(onUnlockRequested),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun FakeHeroCard(
    profile: DisguiseProfile,
    color: Color,
    statusText: String,
    secondaryText: String
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        colors = listOf(color.copy(alpha = 0.92f), color.copy(alpha = 0.58f))
                    )
                )
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                    modifier = Modifier
                        .size(118.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color.White.copy(alpha = 0.20f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(profile.previewIconRes),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(52.dp)
                    )
                }
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = secondaryText,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.86f),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun FakeBusyButtonContent(
    busy: Boolean,
    text: String,
    busyText: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = if (busy) busyText else text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun FakeMetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier.height(82.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun FakeSectionCard(
    rows: List<Pair<String, String>>,
    title: String? = null,
    compact: Boolean = false
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(if (compact) 12.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 11.dp)
        ) {
            title?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = row.first,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = row.second,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1.2f)
                    )
                }
            }
        }
    }
}

@Composable
private fun FakeDetailsCard(rows: List<Pair<String, String>>, compact: Boolean = false) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(if (compact) 12.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 11.dp)
        ) {
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = row.first,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = row.second,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1.2f)
                    )
                }
            }
        }
    }
}

@Composable
private fun FakeInlineRows(rows: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = row.first,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = row.second,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1.2f)
                )
            }
        }
    }
}

@Composable
private fun TimerInputField(
    label: String,
    value: String,
    max: Int,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = { raw ->
            val filtered = raw.filter(Char::isDigit).take(2)
            val bounded = filtered.toIntOrNull()?.coerceAtMost(max)?.toString() ?: filtered
            onValueChange(bounded)
        },
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = MaterialTheme.typography.titleMedium.copy(textAlign = TextAlign.Center),
        modifier = modifier
    )
}

@Composable
private fun FakeDisguiseNotice(noticeRes: Int?) {
    noticeRes?.let { message ->
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Suppress("MultipleAwaitPointerEventScopes")
private fun Modifier.disguiseUnlockHold(onUnlockRequested: () -> Unit): Modifier =
    pointerInput(onUnlockRequested) {
        while (true) {
            awaitPointerEventScope { awaitFirstDown(requireUnconsumed = false) }
            val released = withTimeoutOrNull(DISGUISE_UNLOCK_HOLD_MS) {
                awaitPointerEventScope { waitForUpOrCancellation() }
            }
            if (released == null) {
                onUnlockRequested()
                awaitPointerEventScope { waitForUpOrCancellation() }
            }
        }
    }

private fun findTorchCameraId(context: Context): String? {
    val cameraManager = context.getSystemService(CameraManager::class.java)
    return runCatching {
        cameraManager.cameraIdList.firstOrNull { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            hasFlash && facing == CameraCharacteristics.LENS_FACING_BACK
        }
    }.getOrNull()
}

private fun fakeProfileColor(profile: DisguiseProfile): Color = when (profile) {
    DisguiseProfile.CALCULATOR -> Color(0xFF37474F)
    DisguiseProfile.SYSTEM,
    DisguiseProfile.MAIL,
    DisguiseProfile.TOOLS,
    DisguiseProfile.SYSTEM_TOOLS,
    DisguiseProfile.TASK_MANAGER -> Color(0xFF315A68)
    DisguiseProfile.FLASHLIGHT -> Color(0xFF243447)
    DisguiseProfile.TIMER -> Color(0xFF006D77)
    DisguiseProfile.STOPWATCH -> Color(0xFF6D4CBE)
}

private fun fakeStatusRes(profile: DisguiseProfile): Int = when (profile) {
    DisguiseProfile.SYSTEM,
    DisguiseProfile.MAIL,
    DisguiseProfile.TOOLS,
    DisguiseProfile.SYSTEM_TOOLS,
    DisguiseProfile.TASK_MANAGER -> R.string.fake_system_status
    DisguiseProfile.FLASHLIGHT -> R.string.fake_flashlight_status
    DisguiseProfile.TIMER -> R.string.fake_timer_status
    DisguiseProfile.STOPWATCH -> R.string.fake_stopwatch_status
    DisguiseProfile.CALCULATOR -> R.string.app_name_calculator
}

@Composable
private fun String?.orUnknown(): String =
    this?.takeIf { it.isNotBlank() && it.lowercase(Locale.US) != "unknown" }
        ?: stringResource(R.string.fake_system_unknown)

private data class LiveSystemSnapshot(
    val coreLoads: List<Int>,
    val cpuAverage: Int,
    val memoryAvailableBytes: Long,
    val memoryTotalBytes: Long,
    val storageAvailableBytes: Long,
    val storageTotalBytes: Long,
    val batteryPercent: Int?,
    val batteryTemperatureC: Float?,
    val batteryCharging: Boolean,
    val batteryStatus: Int,
    val batteryHealth: Int,
    val batteryPlugged: Int,
    val batteryVoltageMv: Int?,
    val batteryTechnology: String,
    val batteryCapacityPercent: Int?,
    val cpuTemperatureC: Float?,
    val uptimeMillis: Long
) {
    val memoryUsedBytes: Long = (memoryTotalBytes - memoryAvailableBytes).coerceAtLeast(0L)
    val memoryUsedPercent: Int =
        if (memoryTotalBytes > 0L) ((memoryUsedBytes * 100L) / memoryTotalBytes).toInt().coerceIn(0, 100) else 0
}

@Composable
private fun rememberSensorReading(sensorType: Int, enabled: Boolean = true): FloatArray? {
    val context = LocalContext.current
    val sensorManager = remember(context) { context.getSystemService(SensorManager::class.java) }
    val sensor = remember(sensorManager, sensorType) { sensorManager?.getDefaultSensor(sensorType) }
    var values by remember(sensor) { mutableStateOf<FloatArray?>(null) }

    DisposableEffect(sensorManager, sensor, enabled) {
        if (!enabled || sensorManager == null || sensor == null) {
            values = null
            onDispose { }
        } else {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    values = event.values.copyOf(minOf(event.values.size, 3))
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            onDispose { sensorManager.unregisterListener(listener) }
        }
    }

    return values
}

@Composable
private fun fakeSystemResourceRows(live: LiveSystemSnapshot): List<Pair<String, String>> =
    listOf(
        stringResource(R.string.fake_system_memory) to "${formatBytes(live.memoryUsedBytes)} / ${formatBytes(live.memoryTotalBytes)} (${live.memoryUsedPercent}%)",
        stringResource(R.string.fake_system_storage) to "${formatBytes(live.storageAvailableBytes)} / ${formatBytes(live.storageTotalBytes)}",
        stringResource(R.string.fake_system_battery) to batteryStatusText(live),
        stringResource(R.string.fake_system_cpu_temperature) to (live.cpuTemperatureC?.let(::formatTemperature) ?: stringResource(R.string.fake_system_unknown)),
        stringResource(R.string.fake_system_battery_temperature) to (live.batteryTemperatureC?.let(::formatTemperature) ?: stringResource(R.string.fake_system_unknown)),
        stringResource(R.string.fake_system_uptime) to formatDuration(live.uptimeMillis)
    )

@Composable
private fun fakeDeviceIdentityRows(context: Context, live: LiveSystemSnapshot): List<Pair<String, String>> =
    listOf(
        stringResource(R.string.fake_aida_device_model) to deviceName(context),
        stringResource(R.string.fake_aida_device_type) to deviceType(context),
        stringResource(R.string.fake_aida_manufacturer) to Build.MANUFACTURER.orUnknown(),
        stringResource(R.string.fake_aida_model) to Build.MODEL.orUnknown(),
        stringResource(R.string.fake_aida_brand) to Build.BRAND.orUnknown(),
        stringResource(R.string.fake_aida_board) to Build.BOARD.orUnknown(),
        stringResource(R.string.fake_aida_device) to Build.DEVICE.orUnknown(),
        stringResource(R.string.fake_aida_hardware) to Build.HARDWARE.orUnknown(),
        stringResource(R.string.fake_aida_product) to Build.PRODUCT.orUnknown(),
        stringResource(R.string.fake_aida_bootloader) to Build.BOOTLOADER.orUnknown(),
        stringResource(R.string.fake_aida_installed_ram) to formatBytes(live.memoryTotalBytes),
        stringResource(R.string.fake_aida_available_memory) to formatBytes(live.memoryAvailableBytes),
        stringResource(R.string.fake_aida_internal_total) to formatBytes(live.storageTotalBytes),
        stringResource(R.string.fake_aida_internal_free) to formatBytes(live.storageAvailableBytes)
    )

@Composable
private fun fakeCpuRows(live: LiveSystemSnapshot): List<Pair<String, String>> {
    val cpuInfo = remember { readProcCpuInfo() }
    val cores = live.coreLoads.size
    val current = readCpuCurrentMhz(cores)
    val minMax = readCpuMinMaxMhz(cores)
    val rows = mutableListOf<Pair<String, String>>()
    rows += stringResource(R.string.fake_aida_soc_model) to cpuInfo.firstValue("Hardware", "Processor", "model name", "cpu model").orUnknown()
    rows += stringResource(R.string.fake_aida_core_architecture) to "${cores}x ${cpuInfo.firstValue("Processor", "model name", "Hardware").orUnknown()}"
    rows += stringResource(R.string.fake_aida_instruction_set) to Build.SUPPORTED_ABIS.take(2).joinToString(", ").orUnknown()
    rows += stringResource(R.string.fake_aida_cpu_revision) to cpuInfo.firstValue("CPU revision", "Revision").orUnknown()
    rows += stringResource(R.string.fake_aida_cpu_cores) to cores.toString()
    rows += stringResource(R.string.fake_aida_cpu_clock_range) to formatMhzRange(minMax).orUnknown()
    current.forEachIndexed { index, mhz ->
        rows += stringResource(R.string.fake_aida_core_clock, index + 1) to (mhz?.let { "$it MHz" } ?: stringResource(R.string.fake_aida_sleeping))
    }
    rows += stringResource(R.string.fake_aida_cpu_load) to "${live.cpuAverage}%"
    rows += stringResource(R.string.fake_aida_scaling_governor) to readFirstExistingText(cpuSysFiles("scaling_governor")).orUnknown()
    rows += stringResource(R.string.fake_aida_cpu_abi) to Build.SUPPORTED_ABIS.firstOrNull().orUnknown()
    rows += stringResource(R.string.fake_aida_cpu_features) to cpuInfo.firstValue("Features", "flags").orUnknown()
    return rows
}

@Composable
private fun fakeDisplayRows(context: Context): List<Pair<String, String>> {
    val metrics = context.resources.displayMetrics
    val width = metrics.widthPixels
    val height = metrics.heightPixels
    val widthMm = if (metrics.xdpi > 0f) width / metrics.xdpi * 25.4f else 0f
    val heightMm = if (metrics.ydpi > 0f) height / metrics.ydpi * 25.4f else 0f
    val diagonal = if (widthMm > 0f && heightMm > 0f) {
        kotlin.math.sqrt(widthMm * widthMm + heightMm * heightMm) / 25.4f
    } else {
        0f
    }
    val refreshRate = context.getSystemService(WindowManager::class.java)
        ?.defaultDisplay
        ?.refreshRate
        ?.takeIf { it > 0f }
    val densityBucket = densityBucket(metrics.densityDpi)
    val orientation = if (height >= width) stringResource(R.string.fake_aida_portrait) else stringResource(R.string.fake_aida_landscape)
    val glVersion = context.getSystemService(ActivityManager::class.java)
        ?.deviceConfigurationInfo
        ?.reqGlEsVersion
        ?.let { version -> "OpenGL ES ${version shr 16}.${version and 0xffff}" }
        ?: stringResource(R.string.fake_system_unknown)

    return listOf(
        stringResource(R.string.fake_aida_screen_resolution) to "$width x $height",
        stringResource(R.string.fake_aida_screen_size) to if (widthMm > 0f) String.format(Locale.US, "%.0f mm x %.0f mm", widthMm, heightMm) else stringResource(R.string.fake_system_unknown),
        stringResource(R.string.fake_aida_screen_diagonal) to if (diagonal > 0f) String.format(Locale.US, "%.2f inches", diagonal) else stringResource(R.string.fake_system_unknown),
        stringResource(R.string.fake_aida_screen_density) to "${metrics.densityDpi} dpi ($densityBucket)",
        stringResource(R.string.fake_aida_xdpi_ydpi) to String.format(Locale.US, "%.0f / %.0f dpi", metrics.xdpi, metrics.ydpi),
        stringResource(R.string.fake_aida_refresh_rate) to (refreshRate?.let { String.format(Locale.US, "%.0f Hz", it) } ?: stringResource(R.string.fake_system_unknown)),
        stringResource(R.string.fake_aida_default_orientation) to orientation,
        stringResource(R.string.fake_aida_scaled_density) to String.format(Locale.US, "%.2f", metrics.scaledDensity),
        stringResource(R.string.fake_aida_opengl_es_version) to glVersion
    )
}

@Composable
private fun fakeBatteryRows(live: LiveSystemSnapshot): List<Pair<String, String>> =
    listOf(
        stringResource(R.string.fake_aida_power_source) to batteryPluggedText(live.batteryPlugged),
        stringResource(R.string.fake_aida_level) to (live.batteryPercent?.let { "$it%" } ?: stringResource(R.string.fake_system_unknown)),
        stringResource(R.string.fake_aida_status) to batteryStatusLabel(live.batteryStatus),
        stringResource(R.string.fake_aida_health) to batteryHealthLabel(live.batteryHealth),
        stringResource(R.string.fake_aida_technology) to live.batteryTechnology.ifBlank { stringResource(R.string.fake_system_unknown) },
        stringResource(R.string.fake_aida_temperature) to (live.batteryTemperatureC?.let(::formatTemperature) ?: stringResource(R.string.fake_system_unknown)),
        stringResource(R.string.fake_aida_voltage) to (live.batteryVoltageMv?.let { String.format(Locale.US, "%.3f V", it / 1000f) } ?: stringResource(R.string.fake_system_unknown)),
        stringResource(R.string.fake_aida_capacity) to (live.batteryCapacityPercent?.let { "$it%" } ?: stringResource(R.string.fake_system_unknown))
    )

@Composable
private fun fakeAndroidRows(): List<Pair<String, String>> =
    listOf(
        stringResource(R.string.fake_aida_android_version) to Build.VERSION.RELEASE.orUnknown(),
        stringResource(R.string.fake_aida_api_level) to Build.VERSION.SDK_INT.toString(),
        stringResource(R.string.fake_aida_codename) to Build.VERSION.CODENAME.orUnknown(),
        stringResource(R.string.fake_aida_security_patch) to Build.VERSION.SECURITY_PATCH.ifBlank { stringResource(R.string.fake_system_unknown) },
        stringResource(R.string.fake_aida_build_id) to Build.ID.orUnknown(),
        stringResource(R.string.fake_aida_build_display) to Build.DISPLAY.orUnknown(),
        stringResource(R.string.fake_aida_fingerprint) to Build.FINGERPRINT.orUnknown(),
        stringResource(R.string.fake_aida_incremental) to Build.VERSION.INCREMENTAL.orUnknown(),
        stringResource(R.string.fake_aida_build_type) to Build.TYPE.orUnknown(),
        stringResource(R.string.fake_aida_build_tags) to Build.TAGS.orUnknown(),
        stringResource(R.string.fake_aida_java_runtime) to System.getProperty("java.runtime.version").orUnknown(),
        stringResource(R.string.fake_aida_java_vm) to System.getProperty("java.vm.name").orUnknown(),
        stringResource(R.string.fake_aida_vm_version) to System.getProperty("java.vm.version").orUnknown(),
        stringResource(R.string.fake_aida_vm_heap) to formatBytes(Runtime.getRuntime().maxMemory()),
        stringResource(R.string.fake_aida_kernel_arch) to System.getProperty("os.arch").orUnknown(),
        stringResource(R.string.fake_aida_kernel_version) to readFirstExistingText(listOf(File("/proc/version"))).orUnknown()
    )

@Composable
private fun fakeCameraSections(context: Context): List<Pair<String, List<Pair<String, String>>>> {
    val manager = context.getSystemService(CameraManager::class.java)
    return runCatching {
        manager.cameraIdList.map { id ->
            val characteristics = manager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            val title = when (facing) {
                CameraCharacteristics.LENS_FACING_BACK -> stringResource(R.string.fake_aida_rear_camera)
                CameraCharacteristics.LENS_FACING_FRONT -> stringResource(R.string.fake_aida_front_camera)
                CameraCharacteristics.LENS_FACING_EXTERNAL -> stringResource(R.string.fake_aida_external_camera)
                else -> stringResource(R.string.fake_aida_camera_title, id)
            }
            title to fakeCameraRows(id, characteristics)
        }
    }.getOrDefault(emptyList())
}

@Composable
private fun fakeCameraRows(id: String, characteristics: CameraCharacteristics): List<Pair<String, String>> {
    val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    val jpegSize = map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height }
    val videoSize = videoSizes(map).maxByOrNull { it.width * it.height }
    val maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
    val hardwareLevel = when (characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> stringResource(R.string.fake_system_unknown)
    }
    val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
    val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
    val apertures = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)

    return listOf(
        stringResource(R.string.fake_aida_camera_id) to id,
        stringResource(R.string.fake_aida_resolution) to formatCameraSize(jpegSize),
        stringResource(R.string.fake_aida_video_resolution) to formatCameraSize(videoSize),
        stringResource(R.string.fake_aida_flash) to supportedText(characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true),
        stringResource(R.string.fake_aida_zoom) to supportedText(maxZoom > 1f),
        stringResource(R.string.fake_aida_max_zoom) to String.format(Locale.US, "%.1fx", maxZoom),
        stringResource(R.string.fake_aida_hardware_level) to hardwareLevel,
        stringResource(R.string.fake_aida_sensor_orientation) to (sensorOrientation?.let { "$it°" } ?: stringResource(R.string.fake_system_unknown)),
        stringResource(R.string.fake_aida_focal_length) to focalLengths?.joinToString(", ") { String.format(Locale.US, "%.2f mm", it) }.orUnknown(),
        stringResource(R.string.fake_aida_aperture) to apertures?.joinToString(", ") { "f/${String.format(Locale.US, "%.1f", it)}" }.orUnknown(),
        stringResource(R.string.fake_aida_auto_exposure_lock) to supportedText(characteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true),
        stringResource(R.string.fake_aida_auto_white_balance_lock) to supportedText(characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true),
        stringResource(R.string.fake_aida_video_stabilization) to supportedText((characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.size ?: 0) > 1)
    )
}

@Composable
private fun fakeThermalRows(live: LiveSystemSnapshot): List<Pair<String, String>> {
    val rows = readThermalZoneRows().take(24).toMutableList()
    live.batteryTemperatureC?.let {
        rows += stringResource(R.string.fake_system_battery) to formatTemperature(it)
    }
    if (rows.isEmpty()) {
        rows += stringResource(R.string.fake_aida_temperature) to stringResource(R.string.fake_system_unknown)
    }
    return rows
}

@Composable
private fun fakeSensorRows(
    accelerometer: FloatArray?,
    gyroscope: FloatArray?,
    magnetic: FloatArray?,
    light: FloatArray?,
    proximity: FloatArray?,
    pressure: FloatArray?,
    sensorCount: Int
): List<Pair<String, String>> =
    listOf(
        stringResource(R.string.fake_system_sensor_count) to sensorCount.toString(),
        stringResource(R.string.fake_sensor_accelerometer) to formatVector(accelerometer, "m/s2"),
        stringResource(R.string.fake_sensor_gyroscope) to formatVector(gyroscope, "rad/s"),
        stringResource(R.string.fake_sensor_magnetic) to formatVector(magnetic, "uT"),
        stringResource(R.string.fake_sensor_light) to formatSingleSensor(light, "lx"),
        stringResource(R.string.fake_sensor_proximity) to formatSingleSensor(proximity, "cm"),
        stringResource(R.string.fake_sensor_pressure) to formatSingleSensor(pressure, "hPa")
    )

@Composable
private fun fakeSystemCapabilityRows(context: Context): List<Pair<String, String>> {
    val pm = context.packageManager
    @Composable
    fun yesNo(feature: String): String =
        if (pm.hasSystemFeature(feature)) stringResource(R.string.fake_system_available) else stringResource(R.string.fake_system_unavailable)

    return listOf(
        stringResource(R.string.fake_capability_camera) to yesNo(PackageManager.FEATURE_CAMERA_ANY),
        stringResource(R.string.fake_capability_flash) to yesNo(PackageManager.FEATURE_CAMERA_FLASH),
        stringResource(R.string.fake_capability_biometric) to yesNo(PackageManager.FEATURE_FINGERPRINT),
        stringResource(R.string.fake_capability_nfc) to yesNo(PackageManager.FEATURE_NFC),
        stringResource(R.string.fake_capability_bluetooth) to yesNo(PackageManager.FEATURE_BLUETOOTH_LE),
        stringResource(R.string.fake_capability_gps) to yesNo(PackageManager.FEATURE_LOCATION_GPS),
        stringResource(R.string.fake_capability_wifi_direct) to yesNo(PackageManager.FEATURE_WIFI_DIRECT),
        stringResource(R.string.fake_capability_touchscreen) to yesNo(PackageManager.FEATURE_TOUCHSCREEN)
    )
}

@Composable
private fun fakeSystemPlatformRows(context: Context, live: LiveSystemSnapshot): List<Pair<String, String>> {
    val glVersion = context.getSystemService(ActivityManager::class.java)
        ?.deviceConfigurationInfo
        ?.reqGlEsVersion
        ?.let { version ->
            val major = version shr 16
            val minor = version and 0xffff
            "OpenGL ES $major.$minor"
        }
        ?: stringResource(R.string.fake_system_unknown)

    return listOf(
        stringResource(R.string.fake_system_api_level) to "${Build.VERSION.SDK_INT}",
        stringResource(R.string.fake_system_kernel) to System.getProperty("os.version").orEmpty().ifBlank { stringResource(R.string.fake_system_unknown) },
        stringResource(R.string.fake_system_board) to Build.BOARD.orEmpty().ifBlank { stringResource(R.string.fake_system_unknown) },
        stringResource(R.string.fake_system_hardware) to Build.HARDWARE.orEmpty().ifBlank { stringResource(R.string.fake_system_unknown) },
        stringResource(R.string.fake_system_abis) to Build.SUPPORTED_ABIS.take(2).joinToString(", ").ifBlank { stringResource(R.string.fake_system_unknown) },
        stringResource(R.string.fake_system_cores) to live.coreLoads.size.toString(),
        stringResource(R.string.fake_system_graphics) to glVersion,
        stringResource(R.string.fake_system_build_tags) to Build.TAGS.orEmpty().ifBlank { stringResource(R.string.fake_system_unknown) }
    )
}

private fun readProcCpuInfo(): Map<String, List<String>> =
    runCatching {
        File("/proc/cpuinfo").readLines()
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) null else line.substring(0, separator).trim() to line.substring(separator + 1).trim()
            }
            .groupBy({ it.first }, { it.second })
    }.getOrDefault(emptyMap())

private fun Map<String, List<String>>.firstValue(vararg keys: String): String? {
    keys.forEach { key ->
        this[key]?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return null
}

private fun readCpuCurrentMhz(coreCount: Int): List<Int?> =
    List(coreCount.coerceIn(1, 12)) { index ->
        readFirstExistingText(
            listOf(
                File("/sys/devices/system/cpu/cpu$index/cpufreq/scaling_cur_freq"),
                File("/sys/devices/system/cpu/cpu$index/cpufreq/cpuinfo_cur_freq")
            )
        )?.toLongOrNull()?.let { (it / 1000L).toInt() }
    }

private fun readCpuMinMaxMhz(coreCount: Int): Pair<Int?, Int?> {
    val minValues = mutableListOf<Int>()
    val maxValues = mutableListOf<Int>()
    repeat(coreCount.coerceIn(1, 12)) { index ->
        readFirstExistingText(listOf(File("/sys/devices/system/cpu/cpu$index/cpufreq/cpuinfo_min_freq")))
            ?.toLongOrNull()
            ?.let { minValues += (it / 1000L).toInt() }
        readFirstExistingText(listOf(File("/sys/devices/system/cpu/cpu$index/cpufreq/cpuinfo_max_freq")))
            ?.toLongOrNull()
            ?.let { maxValues += (it / 1000L).toInt() }
    }
    return minValues.minOrNull() to maxValues.maxOrNull()
}

private fun cpuSysFiles(name: String): List<File> =
    List(Runtime.getRuntime().availableProcessors().coerceIn(1, 12)) { index ->
        File("/sys/devices/system/cpu/cpu$index/cpufreq/$name")
    }

private fun readFirstExistingText(files: List<File>): String? =
    files.firstNotNullOfOrNull { file ->
        runCatching {
            if (file.isFile && file.canRead()) file.readText().trim().takeIf { it.isNotBlank() } else null
        }.getOrNull()
    }

private fun formatMhzRange(range: Pair<Int?, Int?>): String {
    val min = range.first
    val max = range.second
    return when {
        min != null && max != null -> "$min - $max MHz"
        max != null -> "$max MHz"
        min != null -> "$min MHz"
        else -> ""
    }
}

private fun densityBucket(densityDpi: Int): String = when {
    densityDpi <= 120 -> "ldpi"
    densityDpi <= 160 -> "mdpi"
    densityDpi <= 240 -> "hdpi"
    densityDpi <= 320 -> "xhdpi"
    densityDpi <= 480 -> "xxhdpi"
    else -> "xxxhdpi"
}

@Composable
private fun deviceType(context: Context): String {
    val smallestDp = context.resources.configuration.smallestScreenWidthDp
    return if (smallestDp >= 600) stringResource(R.string.fake_aida_tablet) else stringResource(R.string.fake_aida_phone)
}

@Composable
private fun batteryStatusLabel(status: Int): String = when (status) {
    BatteryManager.BATTERY_STATUS_CHARGING -> stringResource(R.string.fake_system_charging)
    BatteryManager.BATTERY_STATUS_DISCHARGING -> stringResource(R.string.fake_system_discharging)
    BatteryManager.BATTERY_STATUS_FULL -> stringResource(R.string.fake_aida_battery_full)
    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> stringResource(R.string.fake_aida_not_charging)
    else -> stringResource(R.string.fake_system_unknown)
}

@Composable
private fun batteryHealthLabel(health: Int): String = when (health) {
    BatteryManager.BATTERY_HEALTH_GOOD -> stringResource(R.string.fake_aida_good)
    BatteryManager.BATTERY_HEALTH_OVERHEAT -> stringResource(R.string.fake_aida_overheat)
    BatteryManager.BATTERY_HEALTH_DEAD -> stringResource(R.string.fake_aida_dead)
    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> stringResource(R.string.fake_aida_over_voltage)
    BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> stringResource(R.string.fake_aida_failure)
    BatteryManager.BATTERY_HEALTH_COLD -> stringResource(R.string.fake_aida_cold)
    else -> stringResource(R.string.fake_system_unknown)
}

@Composable
private fun batteryPluggedText(plugged: Int): String = when {
    plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 -> stringResource(R.string.fake_aida_usb_port)
    plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 -> stringResource(R.string.fake_aida_ac_charger)
    plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> stringResource(R.string.fake_aida_wireless)
    else -> stringResource(R.string.fake_system_discharging)
}

@Composable
private fun supportedText(supported: Boolean): String =
    if (supported) stringResource(R.string.fake_aida_supported) else stringResource(R.string.fake_aida_not_supported)

private fun videoSizes(map: StreamConfigurationMap?): List<android.util.Size> =
    runCatching { map?.getOutputSizes(MediaRecorder::class.java)?.toList().orEmpty() }.getOrDefault(emptyList())

@Composable
private fun formatCameraSize(size: android.util.Size?): String =
    size?.let {
        val mp = it.width * it.height / 1_000_000.0
        String.format(Locale.US, "%.1f MP (%d x %d)", mp, it.width, it.height)
    } ?: stringResource(R.string.fake_system_unknown)

private fun readThermalZoneRows(): List<Pair<String, String>> {
    val thermalRoot = File("/sys/class/thermal")
    return runCatching {
        thermalRoot.listFiles()
            ?.asSequence()
            ?.mapNotNull { zone ->
                val type = runCatching { File(zone, "type").readText().trim() }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: zone.name
                val raw = runCatching { File(zone, "temp").readText().trim().toFloat() }.getOrNull()
                raw?.let { value ->
                    val celsius = when {
                        value > 1000f -> value / 1000f
                        value > 100f -> value / 10f
                        else -> value
                    }
                    if (celsius in -40f..140f) type to formatTemperature(celsius) else null
                }
            }
            ?.toList()
            .orEmpty()
    }.getOrDefault(emptyList())
}

private fun readLiveSystemSnapshot(context: Context, tick: Int, taskOptimized: Boolean): LiveSystemSnapshot {
    val coreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(4)
    val coreLoads = fakeCoreLoads(coreCount = coreCount, tick = tick, optimized = taskOptimized)
    val memoryInfo = ActivityManager.MemoryInfo()
    runCatching { context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memoryInfo) }
    val storage = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
    val batteryIntent = runCatching {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull()
    val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val batteryPercent = if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt().coerceIn(0, 100) else null
    val rawBatteryTemp = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
    val batteryTemp = if (rawBatteryTemp != Int.MIN_VALUE) rawBatteryTemp / 10f else null
    val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val health = batteryIntent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
    val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
    val voltage = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
        ?.takeIf { it != Int.MIN_VALUE && it > 0 }
    val technology = batteryIntent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY).orEmpty()
    val batteryManager = context.getSystemService(BatteryManager::class.java)
    val capacity = runCatching { batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }
        .getOrNull()
        ?.takeIf { it in 0..100 }

    return LiveSystemSnapshot(
        coreLoads = coreLoads,
        cpuAverage = coreLoads.average().toInt().coerceIn(0, 100),
        memoryAvailableBytes = memoryInfo.availMem.takeIf { it > 0L } ?: Runtime.getRuntime().freeMemory(),
        memoryTotalBytes = memoryInfo.totalMem.takeIf { it > 0L } ?: Runtime.getRuntime().maxMemory(),
        storageAvailableBytes = storage?.availableBytes ?: availableInternalBytes(context),
        storageTotalBytes = storage?.totalBytes ?: 0L,
        batteryPercent = batteryPercent,
        batteryTemperatureC = batteryTemp,
        batteryCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
        batteryStatus = status,
        batteryHealth = health,
        batteryPlugged = plugged,
        batteryVoltageMv = voltage,
        batteryTechnology = technology,
        batteryCapacityPercent = capacity,
        cpuTemperatureC = readThermalTemperatureC(),
        uptimeMillis = SystemClock.elapsedRealtime()
    )
}

private fun fakeCoreLoads(coreCount: Int, tick: Int, optimized: Boolean): List<Int> {
    val base = if (optimized) 4 else 12
    val range = if (optimized) 18 else 46
    return List(coreCount.coerceIn(1, 12)) { index ->
        val wave = ((tick + 1) * (index + 3) * 7 + index * 19) % range
        (base + wave).coerceIn(1, 96)
    }
}

private fun readThermalTemperatureC(): Float? {
    val thermalRoot = File("/sys/class/thermal")
    return runCatching {
        thermalRoot.listFiles()
            ?.asSequence()
            ?.mapNotNull { zone ->
                val type = runCatching { File(zone, "type").readText().trim().lowercase(Locale.US) }.getOrNull().orEmpty()
                val raw = runCatching { File(zone, "temp").readText().trim().toFloat() }.getOrNull()
                if (raw == null || type.contains("battery")) {
                    null
                } else {
                    val celsius = when {
                        raw > 1000f -> raw / 1000f
                        raw > 100f -> raw / 10f
                        else -> raw
                    }
                    if (celsius in -20f..120f && (type.contains("cpu") || type.contains("soc") || type.contains("thermal") || type.contains("skin"))) {
                        celsius
                    } else {
                        null
                    }
                }
            }
            ?.firstOrNull()
    }.getOrNull()
}

private fun availableInternalBytes(context: Context): Long =
    runCatching {
        val path = context.filesDir?.path ?: Environment.getDataDirectory().path
        StatFs(path).availableBytes
    }.getOrDefault(0L)

private fun deviceName(context: Context): String {
    val manufacturer = Build.MANUFACTURER.orEmpty()
        .replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase(Locale.US) else char.toString()
        }
    val model = Build.MODEL.orEmpty()
    return listOf(manufacturer, model)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .ifBlank { context.getString(R.string.fake_system_android_device) }
}

@Composable
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return stringResource(R.string.fake_system_unknown)
    val gib = bytes / 1024.0 / 1024.0 / 1024.0
    return String.format(Locale.US, "%.1f GB", gib)
}

@Composable
private fun batteryStatusText(live: LiveSystemSnapshot): String {
    val percent = live.batteryPercent?.let { "$it%" } ?: stringResource(R.string.fake_system_unknown)
    val state = if (live.batteryCharging) {
        stringResource(R.string.fake_system_charging)
    } else {
        stringResource(R.string.fake_system_discharging)
    }
    return "$percent · $state"
}

@Composable
private fun formatVector(values: FloatArray?, unit: String): String =
    values?.takeIf { it.isNotEmpty() }
        ?.joinToString(" / ") { String.format(Locale.US, "%.1f", it) }
        ?.let { "$it $unit" }
        ?: stringResource(R.string.fake_system_no_sensor_data)

@Composable
private fun formatSingleSensor(values: FloatArray?, unit: String): String =
    values?.firstOrNull()
        ?.let { String.format(Locale.US, "%.1f %s", it, unit) }
        ?: stringResource(R.string.fake_system_no_sensor_data)

private fun formatTemperature(value: Float): String =
    String.format(Locale.US, "%.1f°C", value)

private fun formatDuration(millis: Long): String {
    val totalMinutes = millis / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0) {
        String.format(Locale.US, "%dh %02dm", hours, minutes)
    } else {
        String.format(Locale.US, "%dm", minutes)
    }
}

private fun formatStopwatch(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}

private fun formatTimer(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

private fun Int?.coerceBounded(min: Int, max: Int): Int =
    (this ?: 0).coerceIn(min, max)
