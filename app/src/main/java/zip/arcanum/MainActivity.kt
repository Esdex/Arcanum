package zip.arcanum

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.core.navigation.AppNavigation
import zip.arcanum.core.security.PinManager
import zip.arcanum.core.theme.AppTheme
import zip.arcanum.crypto.VeraCryptEngine
import zip.arcanum.settings.DisguiseOverlay
import zip.arcanum.settings.SettingsViewModel
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var pinManager: PinManager
    @Inject lateinit var containerRepo: ContainerRepository
    @Inject lateinit var engine: VeraCryptEngine

    private val settingsViewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )

        // On fresh process start, correct any mounted flags left in the DB after a crash or kill.
        // JNI handles never survive process death, so no container is actually mounted.
        if (savedInstanceState == null) {
            lifecycleScope.launch(Dispatchers.IO) { containerRepo.resetMountedState() }
        }

        setContent {
            val themeMode               by settingsViewModel.themeMode.collectAsStateWithLifecycle()
            val isAmoledGlass           by settingsViewModel.isAmoledGlass.collectAsStateWithLifecycle()
            val isDynamicColor          by settingsViewModel.isDynamicColor.collectAsStateWithLifecycle()
            val screenCaptureProtection by settingsViewModel.screenCaptureProtection.collectAsStateWithLifecycle()
            val showDisguiseOverlay     by settingsViewModel.showDisguiseOverlay.collectAsStateWithLifecycle()

            androidx.compose.runtime.LaunchedEffect(screenCaptureProtection) {
                if (screenCaptureProtection) {
                    window.setFlags(
                        android.view.WindowManager.LayoutParams.FLAG_SECURE,
                        android.view.WindowManager.LayoutParams.FLAG_SECURE
                    )
                } else {
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            AppTheme(
                themeMode    = themeMode,
                amoledMode   = isAmoledGlass,
                dynamicColor = isDynamicColor
            ) {
                AppNavigation(pinManager = pinManager)

                if (showDisguiseOverlay) {
                    DisguiseOverlay(
                        onApply = {
                            settingsViewModel.applyDisguise {
                                val intent = packageManager
                                    .getLaunchIntentForPackage(packageName)
                                    ?.addFlags(
                                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                    )
                                startActivity(intent)
                                finishAffinity()
                                android.os.Process.killProcess(android.os.Process.myPid())
                            }
                        },
                        onMaybeLater = { settingsViewModel.dismissDisguiseOverlay() }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        // isFinishing is false on rotation — only close handles when the activity truly exits.
        if (isFinishing) {
            containerRepo.closeAllHandlesSync().forEach { handle ->
                engine.closeContainer(handle)
            }
        }
        super.onDestroy()
    }
}
