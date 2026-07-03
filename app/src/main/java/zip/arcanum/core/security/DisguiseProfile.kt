package zip.arcanum.core.security

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import zip.arcanum.R

enum class DisguiseProfile(
    val prefValue: String,
    val aliasClassName: String,
    @StringRes val labelRes: Int,
    @DrawableRes val launcherIconRes: Int,
    @DrawableRes val launcherRoundIconRes: Int,
    @DrawableRes val previewIconRes: Int,
    val visibleInSettings: Boolean = true
) {
    CALCULATOR(
        prefValue = "calculator",
        aliasClassName = "zip.arcanum.MainActivityCalculator",
        labelRes = R.string.app_name_calculator,
        launcherIconRes = R.mipmap.ic_launcher_calculator,
        launcherRoundIconRes = R.mipmap.ic_launcher_calculator_round,
        previewIconRes = R.drawable.ic_launcher_calc_fg
    ),
    SYSTEM(
        prefValue = "system",
        aliasClassName = "zip.arcanum.MainActivitySystem",
        labelRes = R.string.app_name_system,
        launcherIconRes = R.mipmap.ic_launcher_system,
        launcherRoundIconRes = R.mipmap.ic_launcher_system_round,
        previewIconRes = R.drawable.ic_launcher_system_fg
    ),
    MAIL(
        prefValue = "mail",
        aliasClassName = "zip.arcanum.MainActivityMail",
        labelRes = R.string.app_name_mail,
        launcherIconRes = R.mipmap.ic_launcher_mail,
        launcherRoundIconRes = R.mipmap.ic_launcher_mail_round,
        previewIconRes = R.drawable.ic_launcher_mail_fg,
        visibleInSettings = false
    ),
    FLASHLIGHT(
        prefValue = "flashlight",
        aliasClassName = "zip.arcanum.MainActivityFlashlight",
        labelRes = R.string.app_name_flashlight,
        launcherIconRes = R.mipmap.ic_launcher_flashlight,
        launcherRoundIconRes = R.mipmap.ic_launcher_flashlight_round,
        previewIconRes = R.drawable.ic_launcher_flashlight_fg
    ),
    TOOLS(
        prefValue = "tools",
        aliasClassName = "zip.arcanum.MainActivityTools",
        labelRes = R.string.app_name_tools,
        launcherIconRes = R.mipmap.ic_launcher_tools,
        launcherRoundIconRes = R.mipmap.ic_launcher_tools_round,
        previewIconRes = R.drawable.ic_launcher_tools_fg,
        visibleInSettings = false
    ),
    SYSTEM_TOOLS(
        prefValue = "system_tools",
        aliasClassName = "zip.arcanum.MainActivitySystemTools",
        labelRes = R.string.app_name_system_tools,
        launcherIconRes = R.mipmap.ic_launcher_system_tools,
        launcherRoundIconRes = R.mipmap.ic_launcher_system_tools_round,
        previewIconRes = R.drawable.ic_launcher_system_tools_fg,
        visibleInSettings = false
    ),
    TASK_MANAGER(
        prefValue = "task_manager",
        aliasClassName = "zip.arcanum.MainActivityTaskManager",
        labelRes = R.string.app_name_task_manager,
        launcherIconRes = R.mipmap.ic_launcher_task_manager,
        launcherRoundIconRes = R.mipmap.ic_launcher_task_manager_round,
        previewIconRes = R.drawable.ic_launcher_task_manager_fg,
        visibleInSettings = false
    ),
    TIMER(
        prefValue = "timer",
        aliasClassName = "zip.arcanum.MainActivityTimer",
        labelRes = R.string.app_name_timer,
        launcherIconRes = R.mipmap.ic_launcher_timer,
        launcherRoundIconRes = R.mipmap.ic_launcher_timer_round,
        previewIconRes = R.drawable.ic_launcher_timer_fg
    ),
    STOPWATCH(
        prefValue = "stopwatch",
        aliasClassName = "zip.arcanum.MainActivityStopwatch",
        labelRes = R.string.app_name_stopwatch,
        launcherIconRes = R.mipmap.ic_launcher_stopwatch,
        launcherRoundIconRes = R.mipmap.ic_launcher_stopwatch_round,
        previewIconRes = R.drawable.ic_launcher_stopwatch_fg
    );

    companion object {
        val default = CALCULATOR
        val selectableEntries: List<DisguiseProfile>
            get() = entries.filter { it.visibleInSettings }

        fun canonical(profile: DisguiseProfile): DisguiseProfile =
            if (profile.visibleInSettings) profile else SYSTEM

        fun fromPrefValue(value: String?): DisguiseProfile =
            entries.firstOrNull { it.prefValue == value }?.let(::canonical) ?: default
    }
}
