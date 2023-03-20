package app.revanced.patcher.apk

import app.revanced.patcher.apk.arsc.FileBackend
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable

class File internal constructor(private val path: String, private val apk: Apk, private val backend: FileBackend) :
    Closeable {
    private var changed = false
    var contents = ByteArray(0)
        set(value) {
            changed = true
            field = value
        }

    val exists = backend.exists()

    override fun toString() = path

    init {
        apk.lockFile(path)
        if (exists) {
            apk.logger.info("Reading file: $path")
            contents = backend.load()
            changed = false
        }
    }

    override fun close() {
        if (changed) {
            apk.logger.info("Writing file: $path")
            backend.save(contents)
        }
        apk.unlockFile(path)
    }

    fun readText() = String(contents)

    fun inputStream() = ByteArrayInputStream(contents)
    fun outputStream(bufferSize: Int = 256) = object : ByteArrayOutputStream(bufferSize) {
        override fun close() {
            // If all bytes in the internal buffer are valid, use that to avoid having to copy it.
            this@File.contents = if (count == buf.size) buf else toByteArray()
            super.close()
        }
    }
}