package zip.arcanum.core.security

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import zip.arcanum.MainActivity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CustomDisguiseShortcutManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun applyCustomShortcut(name: String, icon: CustomDisguiseIcon, color: Int) {
        val label = name.trim().take(32)
        if (label.isBlank()) return

        val bitmap = CustomDisguiseVisuals.createIconBitmap(icon, color, sizePx = 144)
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val shortcut = ShortcutInfoCompat.Builder(context, CUSTOM_SHORTCUT_ID)
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithBitmap(bitmap))
            .setIntent(launchIntent)
            .build()

        runCatching { ShortcutManagerCompat.addDynamicShortcuts(context, listOf(shortcut)) }
        if (ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            runCatching { ShortcutManagerCompat.requestPinShortcut(context, shortcut, null) }
        }
    }

    companion object {
        private const val CUSTOM_SHORTCUT_ID = "arcanum_custom_disguise"
    }
}
