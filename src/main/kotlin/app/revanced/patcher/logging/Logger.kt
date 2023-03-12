package app.revanced.patcher.logging

import com.reandroid.apk.APKLogger

interface Logger : APKLogger {
    fun error(msg: String) {}
    fun warn(msg: String) {}
    fun info(msg: String) {}
    fun trace(msg: String) {}
    override fun logError(msg: String?, tr: Throwable?) {
        error("[ARSCLIB ERROR]: $msg")
        if (tr != null) {
            error(tr.stackTraceToString())
        }
    }
    override fun logMessage(msg: String?) = info("[ARSCLIB Info] ${msg}")
    override fun logVerbose(msg: String?) = info("[ARSCLIB Trace] ${msg}")

    object Nop : Logger
}