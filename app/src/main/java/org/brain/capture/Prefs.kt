package org.brain.capture

import android.content.Context
import android.net.Uri

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var vaultUri: Uri?
        get() = sp.getString("vault", null)?.let(Uri::parse)
        set(v) = sp.edit().putString("vault", v?.toString()).apply()

    /** "auto", or a Whisper language code such as "de" or "en". */
    var language: String
        get() = sp.getString("language", "auto")!!
        set(v) = sp.edit().putString("language", v).apply()

    /** How many note cards the main screen shows before "Load more". */
    var cardCount: Int
        get() = sp.getInt("cards", 5)
        set(v) = sp.edit().putInt("cards", v).apply()

    /** "opus" (small, default) or "wav" (lossless, ~8× bigger) for audio saved in the vault. */
    var audioFormat: String
        get() = sp.getString("audio", "opus")!!
        set(v) = sp.edit().putString("audio", v).apply()

    /** The card's share button: send the voice recordings along with the note (true) or leave them out. */
    var shareAudio: Boolean
        get() = sp.getBoolean("share_audio", true)
        set(v) = sp.edit().putBoolean("share_audio", v).apply()

    var inboxFolder: String
        get() = sp.getString("inbox", "Inbox")!!
        set(v) = sp.edit().putString("inbox", v).apply()
}
