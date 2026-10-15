package org.empco.petcam.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as SysSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.empco.petcam.camera.CapabilityCache
import org.empco.petcam.settings.AppLock
import org.empco.petcam.settings.Config
import org.empco.petcam.settings.Lens
import org.empco.petcam.settings.Profile
import org.empco.petcam.settings.NightMode
import org.empco.petcam.settings.Orientation
import org.empco.petcam.settings.VideoQuality
import org.empco.petcam.storage.ClipStore

@Composable
fun SettingsScreen(config: Config, videoResolution: String, update: ((Config) -> Config) -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Section("Camera")
        Label("Capability profile")
        // Unlike video quality, an unstable profile doesn't just look worse — it can crash-loop the
        // camera entirely (some low-end hardware errors repeatedly on the higher-stream profiles
        // until reverted). recordUnstable() only fires after 3 straight failed recoveries, so a
        // a one-off hiccup unrelated to the profile itself (e.g. a background app thrashing memory)
        // shouldn't trip it — but there's no "untested, probably fine" middle ground to show, so a
        // profile that's simply never been tried looks the same as one that's known to work.
        val unstable = remember(config.lens, videoResolution) {
            Profile.entries.filter { CapabilityCache.isUnstable(context, it, config.lens) }.toSet()
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Profile.entries.forEachIndexed { i, pr ->
                val risky = pr in unstable
                SegmentedButton(
                    selected = config.profile == pr,
                    onClick = { update { it.copy(profile = pr) } },
                    shape = SegmentedButtonDefaults.itemShape(i, Profile.entries.size),
                    modifier = Modifier.alpha(if (risky) 0.35f else 1f),
                ) {
                    Text(
                        when (pr) {
                            Profile.BALANCED -> "All / 480p"
                            Profile.EVIDENCE -> "Evidence"
                            Profile.HIRES_LIVE -> "Hi-res + live"
                            Profile.MAX -> "Max"
                        },
                        maxLines = 1,
                    )
                }
            }
        }
        if (unstable.isNotEmpty()) {
            Text(
                "Dimmed options (${unstable.joinToString(", ") { it.name }}) have repeatedly crashed the camera on this " +
                    "phone and were left selectable only so you can retry deliberately — not recommended for unattended use.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "• All / 480p — safest: fewest camera streams, least likely to destabilize weak hardware.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "• Evidence — best quality, but no live view, motion or audio.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "• Hi-res + live — 720p/1080p with live view, motion and audio, if the hardware can hold it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "• Max — tries hardest for resolution and detail; most streams, most likely to misbehave on weak hardware.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SwitchRow("Burn timestamp into the video", config.burnTimestamp, enabled = config.canBurnTimestamp) {
            update { c -> c.copy(burnTimestamp = it) }
        }
        if (!config.canBurnTimestamp) {
            Text(
                "Not available on Max — shown as an overlay on live view, stills and playback instead.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Label("Lens")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Lens.entries.forEachIndexed { i, lens ->
                SegmentedButton(
                    selected = config.lens == lens,
                    onClick = { update { it.copy(lens = lens) } },
                    shape = SegmentedButtonDefaults.itemShape(i, Lens.entries.size),
                ) { Text(lens.name.lowercase().replaceFirstChar { c -> c.uppercase() }) }
            }
        }
        Label("Video quality")
        // Deliberately not `remember`-cached on just (profile, lens): CameraService can record a
        // fresh bind result at any time in the background (it's a separate component writing to
        // the same prefs file), and this needs to pick that up without requiring the user to
        // toggle profile/lens first. Keying on videoResolution (not the whole Status — see
        // MainActivity.App) makes a real bind completion force a fresh read, without also
        // recomposing this whole screen on every motion-detection tick, which only touches
        // unrelated Status fields and happens several times a second.
        val achievedShortEdge = remember(config.profile, config.lens, videoResolution) {
            CapabilityCache.achievedShortEdge(context, config.profile, config.lens)
        }
        fun shortEdgeOf(q: VideoQuality) = when (q) { VideoQuality.SD -> 480; VideoQuality.HD -> 720; VideoQuality.FHD -> 1080 }
        val anyGreyedOut = achievedShortEdge > 0 && VideoQuality.entries.any { shortEdgeOf(it) > achievedShortEdge }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            VideoQuality.entries.forEachIndexed { i, q ->
                val reachable = achievedShortEdge <= 0 || shortEdgeOf(q) <= achievedShortEdge
                SegmentedButton(
                    selected = config.quality == q,
                    enabled = reachable,
                    onClick = { update { it.copy(quality = q) } },
                    shape = SegmentedButtonDefaults.itemShape(i, VideoQuality.entries.size),
                    // Material3's default disabled treatment on an outlined segmented button is too
                    // subtle to register at a glance (especially on a dark theme) — force a visibly
                    // dimmed look rather than trust the theme's default disabled styling.
                    modifier = Modifier.alpha(if (reachable) 1f else 0.35f),
                ) { Text(when (q) { VideoQuality.SD -> "480p"; VideoQuality.HD -> "720p"; VideoQuality.FHD -> "1080p" }) }
            }
        }
        if (anyGreyedOut) {
            Text(
                "This phone has only actually delivered ${achievedShortEdge}p-equivalent video on the \"${config.profile}\" profile " +
                    "— higher options are greyed out rather than silently falling back.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Label("Phone mounting (video is rotated to play upright)")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Orientation.entries.forEachIndexed { i, o ->
                SegmentedButton(
                    selected = config.orientation == o,
                    onClick = { update { it.copy(orientation = o) } },
                    shape = SegmentedButtonDefaults.itemShape(i, Orientation.entries.size),
                ) {
                    Text(
                        when (o) {
                            Orientation.PORTRAIT -> "Upright"
                            Orientation.LANDSCAPE_LEFT -> "Side ◀"
                            Orientation.LANDSCAPE_RIGHT -> "Side ▶"
                            Orientation.UPSIDE_DOWN -> "Flipped"
                        },
                        maxLines = 1,
                    )
                }
            }
        }
        Text(
            "Lay the phone on its side for the widest horizontal view (about 68° instead of 42°). " +
                "\"Side ◀\" means the top of the phone points left; pick the other if the video is upside down.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchRow("Wide 4:3 frame (full sensor, more vertical coverage)", config.wideAspect) {
            update { c -> c.copy(wideAspect = it) }
        }
        SwitchRow("Record sound", config.sound) { update { c -> c.copy(sound = it) } }
        Label("Night vision (no light is ever turned on)")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            NightMode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = config.night == m,
                    onClick = { update { it.copy(night = m) } },
                    shape = SegmentedButtonDefaults.itemShape(i, NightMode.entries.size),
                ) { Text(when (m) { NightMode.AUTO -> "Auto"; NightMode.ON -> "On"; NightMode.OFF -> "Off" }) }
            }
        }
        SwitchRow("Detail stills: full-res frames + close-ups of the moving area after each clip", config.detailStills) {
            update { c -> c.copy(detailStills = it) }
        }
        TextSetting("Label stamped on video", config.cameraLabel) { v -> update { it.copy(cameraLabel = v) } }

        HorizontalDivider()
        Section("Motion mode")
        Label("Sensitivity: ${config.sensitivity} / 10")
        Slider(
            value = config.sensitivity.toFloat(),
            onValueChange = { update { c -> c.copy(sensitivity = it.toInt().coerceIn(1, 10)) } },
            valueRange = 1f..10f, steps = 8,
        )
        IntSetting("Keep recording after motion stops (seconds)", config.postMotionSeconds, 5, 600) { v ->
            update { it.copy(postMotionSeconds = v) }
        }
        IntSetting("Still photo every (seconds, while moving)", config.photoIntervalSeconds, 2, 300) { v ->
            update { it.copy(photoIntervalSeconds = v) }
        }

        HorizontalDivider()
        Section("Continuous mode")
        IntSetting("Clip length (minutes)", config.clipMinutes, 1, 60) { v -> update { it.copy(clipMinutes = v) } }

        HorizontalDivider()
        Section("Photo-only mode")
        Text(
            "No video — just full-res stills. For hardware too weak to run the camera pipeline reliably. Motion trigger bursts while moving, then tapers off; continuous trigger ignores motion entirely.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IntSetting("Burst photo every (seconds, while moving)", config.photoBurstIntervalSeconds, 1, 60) { v ->
            update { it.copy(photoBurstIntervalSeconds = v) }
        }
        IntSetting(
            "Then every (seconds) — also the continuous-trigger cadence",
            config.photoOnlyIntervalSeconds, 2, 300,
        ) { v -> update { it.copy(photoOnlyIntervalSeconds = v) } }
        IntSetting("...for this long after motion stops (seconds)", config.photoOnlyPostMotionSeconds, 0, 3600) { v ->
            update { it.copy(photoOnlyPostMotionSeconds = v) }
        }

        HorizontalDivider()
        Section("Storage")
        // Recomputed on every recomposition rather than remembered, so inserting an SD card later
        // enables the toggle without restarting the app.
        val sdAvailable = ClipStore.sdCardAvailable(context)
        SwitchRow(
            if (sdAvailable) "Store footage on SD card" else "Store footage on SD card (none detected)",
            config.useSdCard && sdAvailable,
            enabled = sdAvailable,
        ) {
            update { c -> c.copy(useSdCard = it) }
        }
        if (!sdAvailable) {
            Text(
                "No removable storage detected — footage is recorded to the phone's built-in storage.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IntSetting("Delete oldest footage when free space is below (%)", config.minFreePercent, 1, 50) { v ->
            update { it.copy(minFreePercent = v) }
        }
        IntSetting("Extra fixed cap for footage (MB, 0 = off)", config.storageLimitMb, 0, 500_000) { v ->
            update { it.copy(storageLimitMb = v) }
        }
        Text(
            "Footage is kept until the storage is nearly full; then the oldest clips and photos go first.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchRow("Start camera automatically when the app opens", config.autoStart) { update { c -> c.copy(autoStart = it) } }

        HorizontalDivider()
        Section("Remote access (optional)")
        Text(
            "Everything works without a server: footage is recorded to this phone's built-in storage "
                + "(or its SD card) only. Point the app at your own server — the included Cloudflare "
                + "Worker in cloudflare/ is one free option — to enable off-site backup and remote "
                + "live view. No third-party service is required.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextSetting("Server URL", config.cloudUrl, placeholder = "https://camera.yourname.example") { v ->
            update { it.copy(cloudUrl = v.trim()) }
        }
        TextSetting("Device token", config.deviceToken, secret = true) { v -> update { it.copy(deviceToken = v.trim()) } }
        val cloudReady = config.cloudUrl.isNotBlank() && config.deviceToken.isNotBlank()
        SwitchRow("Upload clips and photos", config.uploadEnabled, enabled = cloudReady) {
            update { c -> c.copy(uploadEnabled = it) }
        }
        SwitchRow("Upload on Wi-Fi only", config.uploadWifiOnly, enabled = cloudReady && config.uploadEnabled) {
            update { c -> c.copy(uploadWifiOnly = it) }
        }
        SwitchRow("Live view (stream when someone is watching)", config.liveEnabled, enabled = cloudReady) {
            update { c -> c.copy(liveEnabled = it) }
        }
        IntSetting(
            "Live view frame rate (fps)", config.liveFps, 1, 15,
            enabled = cloudReady && config.liveEnabled,
        ) { v -> update { it.copy(liveFps = v) } }
        if (!cloudReady) {
            Text(
                "Local-only: recordings stay on this phone. Enter a server URL and device token "
                    + "to enable uploads and live view.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()
        Section("Battery")
        val pm = context.getSystemService(PowerManager::class.java)
        val ignoring = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        Text(
            if (ignoring) "Battery optimisation is off for this app (good)."
            else "Turn off battery optimisation so Android does not kill the recorder while the screen is locked.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (!ignoring) {
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent(SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                )
            }) { Text("Disable battery optimisation") }
        }

        HorizontalDivider()
        Section("Privacy")
        val appLock = remember { AppLock.get(context) }
        var pinEnabled by remember { mutableStateOf(appLock.isEnabled) }
        var dialog by remember { mutableStateOf<PinDialog?>(null) }
        Text(
            "Require a PIN to open the app — so if the phone is unlocked, no one else can see the camera or footage without it. "
                + "Recording keeps running either way; this only locks the app's screen.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchRow("Require PIN to open app", pinEnabled) { on ->
            dialog = if (on) PinDialog.Setup else PinDialog.ConfirmDisable
        }
        if (pinEnabled) {
            OutlinedButton(onClick = { dialog = PinDialog.Setup }) { Text("Change PIN") }
        }
        when (dialog) {
            PinDialog.Setup -> PinSetupDialog(
                onConfirm = { pin -> appLock.setPin(pin); pinEnabled = true; dialog = null },
                onDismiss = { dialog = null },
            )
            PinDialog.ConfirmDisable -> PinVerifyDialog(
                lock = appLock,
                title = "Enter current PIN to turn it off",
                onVerified = { appLock.disable(); pinEnabled = false; dialog = null },
                onDismiss = { dialog = null },
            )
            null -> {}
        }
        if (pinEnabled) {
            Text(
                "If you forget the PIN, the only recovery is clearing the app's storage in Android Settings, "
                    + "which also erases the remote-access settings above.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private enum class PinDialog { Setup, ConfirmDisable }

@Composable private fun Section(t: String) = Text(t, style = MaterialTheme.typography.titleMedium)
@Composable private fun Label(t: String) = Text(t, style = MaterialTheme.typography.bodyMedium)

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun IntSetting(label: String, value: Int, min: Int, max: Int, enabled: Boolean = true, onCommit: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t.filter { it.isDigit() }
            text.toIntOrNull()?.let { n -> if (n in min..max) onCommit(n) }
        },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supportingText = { Text("$min – $max") },
        modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f),
    )
}

@Composable
private fun TextSetting(
    label: String, value: String, placeholder: String = "", secret: Boolean = false, onCommit: (String) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onCommit(it) },
        label = { Text(label) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
    )
}
