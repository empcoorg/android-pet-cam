package org.empco.petcam.settings

import android.content.Context
import androidx.core.content.edit
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Local 4-digit PIN gate for opening the app UI. Independent of [Config]: it never affects
 * recording, never leaves the device, and isn't part of the camera settings the Worker can see.
 * Only the salted hash is stored, never the PIN itself.
 */
class AppLock private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("petcam_lock", Context.MODE_PRIVATE)

    val isEnabled: Boolean get() = prefs.contains("hash")

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit {
            putString("salt", salt.toHex())
            putString("hash", hash(pin, salt))
            putInt("failCount", 0)
            putLong("lockedUntil", 0)
        }
    }

    fun disable() = prefs.edit { clear() }

    fun verify(pin: String): Boolean {
        val salt = prefs.getString("salt", null)?.fromHex() ?: return false
        val expected = prefs.getString("hash", null) ?: return false
        return hash(pin, salt) == expected
    }

    /** Escalating lockout after repeated wrong PINs; returns the new lockedUntil timestamp (0 = not locked). */
    fun recordFailure(): Long {
        val count = prefs.getInt("failCount", 0) + 1
        val lockMs = when {
            count < 3 -> 0L
            count < 5 -> 15_000L
            count < 7 -> 60_000L
            else -> 300_000L
        }
        val until = if (lockMs > 0) System.currentTimeMillis() + lockMs else 0L
        prefs.edit { putInt("failCount", count); putLong("lockedUntil", until) }
        return until
    }

    fun recordSuccess() = prefs.edit { putInt("failCount", 0); putLong("lockedUntil", 0) }

    val lockedUntil: Long get() = prefs.getLong("lockedUntil", 0)

    private fun hash(pin: String, salt: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        md.update(pin.toByteArray(Charsets.UTF_8))
        return md.digest().toHex()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun String.fromHex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    companion object {
        @Volatile private var instance: AppLock? = null
        fun get(context: Context): AppLock = instance ?: synchronized(this) {
            instance ?: AppLock(context.applicationContext).also { instance = it }
        }
    }
}
