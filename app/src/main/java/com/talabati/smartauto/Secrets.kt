package com.talabati.smartauto

import android.util.Base64

/** تشفير بسيط (XOR) لعناوين الخادم حتى لا تظهر كنص صريح بفك APK */
private object Sec {
    private val key = byteArrayOf(83, -13, 39, 102, -89, 13, 19, -45)

    fun d(b64: String): String {
        val data = Base64.decode(b64, Base64.NO_WRAP)
        val out = ByteArray(data.size)
        for (i in data.indices) out[i] = (data[i].toInt() xor key[i % key.size].toInt()).toByte()
        return String(out, Charsets.UTF_8)
    }
}

object Endpoints {
    val BASE: String get() = Sec.d("O4dTFtQ3PPwyh0gTijQn62eQCgLCa3KmP4cKFNNpcf02hlUJ12g+pDaAU1eJa3qhNpFGFcJpcqcykUYVwiNyoyM=")
    val LICENSES: String get() = Sec.d("P5pEA8l+dqA=")
    val REQUESTS: String get() = Sec.d("IZZWE8J+Z6A=")
}
