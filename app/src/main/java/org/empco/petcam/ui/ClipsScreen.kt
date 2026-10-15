package org.empco.petcam.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.Build
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.empco.petcam.PlayerActivity
import org.empco.petcam.storage.Clip
import org.empco.petcam.storage.ClipStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ClipsScreen(store: ClipStore, refreshKey: Int, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var clips by remember { mutableStateOf<List<Clip>>(emptyList()) }
    var tick by remember { mutableStateOf(0) }
    var toDelete by remember { mutableStateOf<Clip?>(null) }
    var photoToShow by remember { mutableStateOf<Clip?>(null) }

    LaunchedEffect(refreshKey, tick) { clips = withContext(Dispatchers.IO) { store.list() } }

    if (clips.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No clips yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
        items(clips, key = { it.file.absolutePath }) { clip ->
            ClipRow(
                clip,
                onOpen = {
                    if (clip.isVideo) {
                        context.startActivity(
                            Intent(context, PlayerActivity::class.java)
                                .putExtra(PlayerActivity.EXTRA_PATH, clip.file.absolutePath)
                        )
                    } else photoToShow = clip
                },
                onSave = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { store.saveToGallery(clip) }
                        snackbar.showSnackbar(if (ok) "Saved to ${if (clip.isVideo) "Movies" else "Pictures"}/PetCam" else "Save failed")
                    }
                },
                onShare = {
                    val uri = store.shareUri(clip)
                    val send = Intent(Intent.ACTION_SEND)
                        .setType(if (clip.isVideo) "video/mp4" else "image/jpeg")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, "Share clip"))
                },
                onDelete = { toDelete = clip },
            )
        }
    }

    toDelete?.let { clip ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete ${clip.name}?") },
            text = { Text("This removes the local copy. Cloud copies are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { store.delete(clip) }
                        toDelete = null
                        tick++
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }

    photoToShow?.let { clip ->
        Dialog(onDismissRequest = { photoToShow = null }) {
            val bmp by produceState<Bitmap?>(null, clip) {
                value = withContext(Dispatchers.IO) {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    BitmapFactory.decodeFile(clip.file.absolutePath, opts)
                }
            }
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Black)
                    .clickable { photoToShow = null },
            ) {
                bmp?.let { Image(it.asImageBitmap(), clip.name, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit) }
            }
        }
    }
}

private val dateFmt = SimpleDateFormat("EEE d MMM · HH:mm:ss", Locale.getDefault())

@Composable
private fun ClipRow(clip: Clip, onOpen: () -> Unit, onSave: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    val thumb by produceState<Bitmap?>(null, clip.file.absolutePath) {
        value = withContext(Dispatchers.IO) {
            try {
                if (clip.isVideo) {
                    if (Build.VERSION.SDK_INT >= 29) ThumbnailUtils.createVideoThumbnail(clip.file, Size(192, 192), null)
                    else @Suppress("DEPRECATION") ThumbnailUtils.createVideoThumbnail(
                        clip.file.absolutePath, android.provider.MediaStore.Video.Thumbnails.MINI_KIND
                    )
                } else {
                    BitmapFactory.decodeFile(clip.file.absolutePath, BitmapFactory.Options().apply { inSampleSize = 16 })
                }
            } catch (_: Exception) { null }
        }
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(72.dp, 54.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Icon(
                if (clip.isVideo) Icons.Default.PlayArrow else Icons.Default.Photo, null,
                tint = Color.White.copy(alpha = 0.85f),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(dateFmt.format(Date(clip.startTime)), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (clip.isVideo) Icons.Default.Videocam else Icons.Default.Photo, null,
                    Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${clip.mode} · ${formatBytes(clip.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (clip.uploaded) Icon(Icons.Default.CloudDone, "Uploaded", Modifier.size(14.dp), tint = CloudGreen)
            }
        }
        IconButton(onClick = onSave) { Icon(Icons.Default.SaveAlt, "Save to gallery") }
        IconButton(onClick = onShare) { Icon(Icons.Default.Share, "Share") }
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
    }
}
