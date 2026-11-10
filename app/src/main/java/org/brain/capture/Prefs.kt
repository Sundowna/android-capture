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

    var inboxFolder: String
        get() = sp.getString("inbox", "Inbox")!!
        set(v) = sp.edit().putString("inbox", v).apply()
}
