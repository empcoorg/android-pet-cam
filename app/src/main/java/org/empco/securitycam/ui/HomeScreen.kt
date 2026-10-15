package org.empco.securitycam.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import org.empco.securitycam.camera.CameraService
import org.empco.securitycam.settings.CaptureMode
import org.empco.securitycam.settings.Config
import org.empco.securitycam.settings.MediaType

@Composable
fun HomeScreen(
    status: CameraService.Status,
    preview: Bitmap?,
    config: Config,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onMode: (CaptureMode) -> Unit,
    onMediaType: (MediaType) -> Unit,
    onSnapshot: () -> Unit,
    onBlackout: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)).background(Color.Black),
        ) {
            if (preview != null && status.running) {
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = "Camera preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text(
                    when {
                        !status.running -> "Camera off"
                        !status.cameraReady -> "Starting camera…"
                        else -> "Waiting for frames…"
                    },
                    Modifier.align(Alignment.Center), color = Color.White,
                )
            }
            Row(Modifier.align(Alignment.TopStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (status.recording) Badge("REC", RecRed)
                if (status.motion) Badge("MOTION", MotionAmber)
                if (status.liveViewers > 0) Badge("LIVE ${status.liveViewers}", LiveBlue)
                else if (status.liveConnected) Badge("CLOUD", CloudGreen)
            }
            if (status.running && status.cameraReady) {
                Text(
                    "motion %.1f%%".format(status.motionScore * 100),
                    Modifier.align(Alignment.BottomEnd).padding(8.dp),
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        status.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (status.running && status.lowEnd) {
            Text(
                "Low-power profile: 720p max, 2 MP stills, live view ≤ 4 fps.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (status.stillsDegraded) {
            Text(
                "This camera cannot run video + stills together; photos are taken from the analysis stream.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (config.mediaType == MediaType.PHOTO_ONLY) {
            Text(
                if (config.mode == CaptureMode.MOTION)
                    "Photo-only + motion: bursts every ${config.photoBurstIntervalSeconds}s while moving, " +
                        "then every ${config.photoOnlyIntervalSeconds}s for ${config.photoOnlyPostMotionSeconds}s after — " +
                        "no video, no live view. Lowest CPU/RAM use."
                else
                    "Photo-only + continuous: full-res photo every ${config.photoOnlyIntervalSeconds}s — " +
                        "no video, no live view, no motion analysis. Lowest CPU/RAM use.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = config.mediaType == MediaType.VIDEO,
                onClick = { onMediaType(MediaType.VIDEO) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("Video") }
            SegmentedButton(
                selected = config.mediaType == MediaType.PHOTO_ONLY,
                onClick = { onMediaType(MediaType.PHOTO_ONLY) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("Photo only") }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = config.mode == CaptureMode.MOTION,
                onClick = { onMode(CaptureMode.MOTION) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("Motion") }
            SegmentedButton(
                selected = config.mode == CaptureMode.CONTINUOUS,
                onClick = { onMode(CaptureMode.CONTINUOUS) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("Continuous") }
        }

        Text(
            (if (status.running) "${formatBytes(status.usedBytes)} on ${status.storageLocation} · ${formatBytes(status.freeBytes)} free · "
            else "") + "${status.clipsRecorded} clips · ${status.photosTaken} photos this session",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (status.running) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Default.Stop, null); Spacer(Modifier.width(8.dp)); Text("Stop")
                }
            } else {
                Button(onClick = onStart, modifier = Modifier.weight(1f).height(56.dp)) {
                    Icon(Icons.Default.Videocam, null); Spacer(Modifier.width(8.dp)); Text("Start")
                }
            }
            FilledTonalButton(
                onClick = onSnapshot,
                enabled = status.cameraReady,
                modifier = Modifier.height(56.dp),
            ) {
                Icon(Icons.Default.PhotoCamera, null); Spacer(Modifier.width(8.dp)); Text("Photo")
            }
        }
        FilledTonalButton(
            onClick = onBlackout,
            enabled = status.running,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Icon(Icons.Default.DarkMode, null); Spacer(Modifier.width(8.dp)); Text("Screen off (tap to wake)")
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text,
        Modifier.background(color, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
    )
}
