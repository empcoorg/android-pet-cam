package org.empco.securitycam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import org.empco.securitycam.settings.AppLock

/** Full-screen PIN gate shown whenever the app is opened or resumed while [AppLock] is enabled. */
@Composable
fun PinLockScreen(lock: AppLock, onUnlocked: () -> Unit) {
    var entered by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }
    var lockedUntil by remember { mutableLongStateOf(lock.lockedUntil) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(lockedUntil) {
        while (System.currentTimeMillis() < lockedUntil) {
            now = System.currentTimeMillis()
            delay(500)
        }
        now = System.currentTimeMillis()
    }
    val locked = now < lockedUntil

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Locked", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(4) { i ->
                Box(
                    Modifier.size(16.dp).clip(CircleShape).background(
                        if (i < entered.length) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                    )
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            when {
                locked -> "Too many attempts. Try again in ${(lockedUntil - now) / 1000 + 1}s."
                showError -> "Incorrect PIN"
                else -> ""
            },
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))
        Keypad(enabled = !locked) { key ->
            showError = false
            when (key) {
                "⌫" -> if (entered.isNotEmpty()) entered = entered.dropLast(1)
                else -> if (entered.length < 4) {
                    entered += key
                    if (entered.length == 4) {
                        if (lock.verify(entered)) {
                            lock.recordSuccess()
                            onUnlocked()
                        } else {
                            lockedUntil = lock.recordFailure()
                            showError = true
                        }
                        entered = ""
                    }
                }
            }
        }
    }
}

/** Two-step "enter new PIN, then confirm it" dialog used from Settings to set or change the PIN. */
@Composable
fun PinSetupDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var entered by remember { mutableStateOf("") }
    var mismatch by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    if (first == null) "Choose a 4-digit PIN" else "Confirm PIN",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    repeat(4) { i ->
                        Box(
                            Modifier.size(14.dp).clip(CircleShape).background(
                                if (i < entered.length) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                            )
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    if (mismatch) "PINs didn't match — try again" else "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(16.dp))
                Keypad(enabled = true) { key ->
                    mismatch = false
                    when (key) {
                        "⌫" -> if (entered.isNotEmpty()) entered = entered.dropLast(1)
                        else -> if (entered.length < 4) {
                            entered += key
                            if (entered.length == 4) {
                                val f = first
                                if (f == null) {
                                    first = entered
                                    entered = ""
                                } else if (entered == f) {
                                    onConfirm(entered)
                                } else {
                                    mismatch = true
                                    first = null
                                    entered = ""
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Compact "enter current PIN to continue" dialog, used to confirm before turning the lock off. */
@Composable
fun PinVerifyDialog(lock: AppLock, title: String, onVerified: () -> Unit, onDismiss: () -> Unit) {
    var entered by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    repeat(4) { i ->
                        Box(
                            Modifier.size(14.dp).clip(CircleShape).background(
                                if (i < entered.length) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                            )
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    if (showError) "Incorrect PIN" else "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(16.dp))
                Keypad(enabled = true) { key ->
                    showError = false
                    when (key) {
                        "⌫" -> if (entered.isNotEmpty()) entered = entered.dropLast(1)
                        else -> if (entered.length < 4) {
                            entered += key
                            if (entered.length == 4) {
                                if (lock.verify(entered)) onVerified() else showError = true
                                entered = ""
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun Keypad(enabled: Boolean, onKey: (String) -> Unit) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("", "0", "⌫"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (key in row) {
                    Box(
                        Modifier.size(72.dp).clip(CircleShape)
                            .background(if (key.isEmpty()) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surfaceVariant)
                            .let { m -> if (key.isNotEmpty() && enabled) m.clickable { onKey(key) } else m },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (key.isNotEmpty()) Text(key, style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        }
    }
}
