package zip.arcanum.core.security

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import zip.arcanum.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DisguiseManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPreferences
) {
    companion object {
        private const val ALIAS_ARCANUM    = "zip.arcanum.MainActivityArcanum"
        private const val ALIAS_CALCULATOR = "zip.arcanum.MainActivityCalculator"
    }

    fun isDisguiseApplied(): Boolean {
        if (BuildConfig.DEBUG) return false
        return context.packageManager.getComponentEnabledSetting(
            ComponentName(context, ALIAS_CALCULATOR)
        ) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }

    suspend fun apply() {
        if (!BuildConfig.DEBUG) {
            val pm = context.packageManager
            pm.setComponentEnabledSetting(
                ComponentName(context, ALIAS_CALCULATOR),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                ComponentName(context, ALIAS_ARCANUM),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
        prefs.setDisguisePromptShown(true)
    }

    suspend fun reset() {
        if (!BuildConfig.DEBUG) {
            val pm = context.packageManager
            pm.setComponentEnabledSetting(
                ComponentName(context, ALIAS_ARCANUM),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                ComponentName(context, ALIAS_CALCULATOR),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
        prefs.setDisguisePromptShown(false)
    }
}
