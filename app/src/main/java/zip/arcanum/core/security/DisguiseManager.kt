package zip.arcanum.core.security

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DisguiseManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPreferences
) {
    companion object {
        private const val ALIAS_ARCANUM = "zip.arcanum.MainActivityArcanum"
    }

    fun isDisguiseApplied(): Boolean =
        appliedProfile() != null

    fun appliedProfile(): DisguiseProfile? =
        rawAppliedProfile()?.let(DisguiseProfile::canonical)

    private fun rawAppliedProfile(): DisguiseProfile? =
        DisguiseProfile.entries.firstOrNull { profile ->
            context.packageManager.getComponentEnabledSetting(ComponentName(context, profile.aliasClassName)) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }

    suspend fun apply(profile: DisguiseProfile) {
        val selectedProfile = DisguiseProfile.canonical(profile)
        val pm = context.packageManager
        DisguiseProfile.entries.forEach { item ->
            pm.setComponentEnabledSetting(
                ComponentName(context, item.aliasClassName),
                if (item == selectedProfile) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
        pm.setComponentEnabledSetting(
            ComponentName(context, ALIAS_ARCANUM),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
        prefs.setDisguisePromptShown(true)
        prefs.setDisguiseEnabled(true)
        prefs.setDisguiseProfile(selectedProfile)
    }

    suspend fun normalizeLegacyAliases() {
        val rawProfile = rawAppliedProfile() ?: return
        if (!rawProfile.visibleInSettings) {
            apply(DisguiseProfile.SYSTEM)
        }
    }

    suspend fun reset() {
        val pm = context.packageManager
        pm.setComponentEnabledSetting(
            ComponentName(context, ALIAS_ARCANUM),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
        DisguiseProfile.entries.forEach { profile ->
            pm.setComponentEnabledSetting(
                ComponentName(context, profile.aliasClassName),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
        prefs.setDisguiseEnabled(false)
    }
}
