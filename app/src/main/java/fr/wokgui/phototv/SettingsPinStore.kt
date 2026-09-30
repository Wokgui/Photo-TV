package fr.wokgui.phototv

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

object SettingsPinStore {
    private const val PREFS = "photo_tv_settings_lock"
    private const val SALT = "salt"
    private const val HASH = "hash"

    fun hasPin(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return !prefs.getString(SALT, null).isNullOrBlank() &&
            !prefs.getString(HASH, null).isNullOrBlank()
    }

    fun setPin(context: Context, pin: String) {
        require(pin.matches(Regex("\\d{4,8}")))
        val salt = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val hash = hash(pin, salt)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .apply()
    }

    fun verify(context: Context, pin: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val salt = prefs.getString(SALT, null)?.let {
            runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull()
        } ?: return false
        val expected = prefs.getString(HASH, null)?.let {
            runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull()
        } ?: return false
        return MessageDigest.isEqual(expected, hash(pin, salt))
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        repeat(20_000) {
            digest.update(pin.toByteArray(Charsets.UTF_8))
            digest.update(salt)
        }
        return digest.digest()
    }
}
