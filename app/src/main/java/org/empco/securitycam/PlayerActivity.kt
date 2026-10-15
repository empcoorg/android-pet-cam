package org.empco.securitycam

import android.graphics.Color
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity

/** Minimal full-screen player for locally recorded clips. */
class PlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null) { finish(); return }

        val video = VideoView(this)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                video,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
                ).apply { gravity = android.view.Gravity.CENTER },
            )
        }
        setContentView(root)
        video.setMediaController(MediaController(this).also { it.setAnchorView(video) })
        video.setVideoPath(path)
        video.setOnPreparedListener { video.start() }
        video.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, "Cannot play this clip", Toast.LENGTH_SHORT).show(); finish(); true
        }
    }

    companion object { const val EXTRA_PATH = "path" }
}
