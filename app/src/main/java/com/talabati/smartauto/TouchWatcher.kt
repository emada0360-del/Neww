package com.talabati.smartauto

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * يراقب لمسات إصبع المستخدم عبر Shizuku (getevent) بدون ما يعترض اللمسة.
 * النقرات اللي ينفذها التطبيق نفسه (input tap) ما تظهر هنا.
 */
object TouchWatcher {

    @Volatile
    private var pending = 0L
    private var process: Process? = null
    private var thread: Thread? = null
    @Volatile
    private var running = false
    private var lastDown = 0L
    private var lastStartTry = 0L

    fun pendingAt(): Long = pending

    fun consume() {
        pending = 0L
    }

    @Synchronized
    fun ensureRunning() {
        if (running && thread?.isAlive == true) return
        val now = System.currentTimeMillis()
        if (now - lastStartTry < 1500L) return
        lastStartTry = now
        val p = AdbTapExecutor.spawn(arrayOf("getevent", "-l")) ?: return
        process = p
        running = true
        val t = Thread {
            try {
                val reader = BufferedReader(InputStreamReader(p.inputStream))
                while (running) {
                    val line = reader.readLine() ?: break
                    if (isDown(line)) {
                        val t0 = System.currentTimeMillis()
                        if (t0 - lastDown > 150L) {
                            lastDown = t0
                            pending = t0
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                running = false
            }
        }
        t.isDaemon = true
        t.start()
        thread = t
    }

    @Synchronized
    fun stop() {
        if (!running && process == null) return
        running = false
        try {
            process?.destroy()
        } catch (_: Throwable) {
        }
        process = null
        thread = null
        pending = 0L
    }

    private fun isDown(line: String): Boolean {
        val l = line.trim()
        if (l.contains("BTN_TOUCH") && l.endsWith("DOWN")) return true
        if (l.contains("ABS_MT_TRACKING_ID") && !l.endsWith("ffffffff")) return true
        return false
    }
}
