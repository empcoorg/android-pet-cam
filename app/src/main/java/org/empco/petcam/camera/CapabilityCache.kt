package org.empco.petcam.camera

import android.content.Context
import androidx.core.content.edit
import org.empco.petcam.settings.Lens
import org.empco.petcam.settings.Profile

/**
 * The smallest video short-edge CameraService has actually achieved for a given profile+lens —
 * observed capability, not a user setting. A requested quality (SD/HD/FHD) is only a target;
 * CameraX silently falls back when the real stream combination (video + analysis + overlay, which
 * varies per profile) can't deliver it. Settings uses this to grey out quality options already
 * proven unreachable, instead of letting the UI claim a resolution the phone never actually records.
 */
object CapabilityCache {
    private const val PREFS = "petcam_capability"

    fun recordAchieved(context: Context, profile: Profile, lens: Lens, shortEdge: Int) {
        if (shortEdge <= 0) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "shortEdge_${profile.name}_${lens.name}"
        // Keep the best ever observed for this combination — the result is a deterministic
        // hardware/stream-combination limit, not something that varies run to run.
        if (shortEdge > prefs.getInt(key, 0)) prefs.edit { putInt(key, shortEdge) }
    }

    /** 0 means "never bound yet" — callers should treat that as no data, not as a limit of zero. */
    fun achievedShortEdge(context: Context, profile: Profile, lens: Lens): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("shortEdge_${profile.name}_${lens.name}", 0)

    /**
     * Marks a profile+lens as having crash-looped on this device (repeated camera errors without
     * ever reaching a stable bind) — a different, higher-stakes signal than a resolution shortfall:
     * this profile doesn't just look worse here, selecting it breaks the camera. Sticky once set,
     * same reasoning as achieved resolution: a camera HAL/stream-combination failure like this is a
     * deterministic property of this device, not a one-off (unlike an unrelated system hiccup, which
     * wouldn't repeatedly manifest as camera-level errors specifically).
     */
    fun recordUnstable(context: Context, profile: Profile, lens: Lens) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean("unstable_${profile.name}_${lens.name}", true)
        }
    }

    fun isUnstable(context: Context, profile: Profile, lens: Lens): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("unstable_${profile.name}_${lens.name}", false)
}
