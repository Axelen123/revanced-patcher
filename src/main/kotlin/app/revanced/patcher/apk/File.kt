package app.revanced.patcher.apk

import app.revanced.patcher.apk.arsc.FileBackend
import app.revanced.patcher.util.SmartByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset

class File internal constructor(private val path: String, private val apk: Apk, private val backend: FileBackend) :
    Closeable {
    private var changed = false
    var contents = ByteArray(0)
        set(value) {
            changed = true
            field = value
        }

    val exists = backend.exists()

    /**
     * Returns null and closes the [File] if it does not exist.
     * @return file The current [File], or null.
     */
    fun existsOrNull(): File? = if (!exists) {
        close()
        null
    } else this

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
    fun writeText(string: String, charset: Charset = Charsets.UTF_8) {
        contents = string.toByteArray(charset)
    }

    fun inputStream(): InputStream = ByteArrayInputStream(contents)
    fun outputStream(bufferSize: Int = 256): OutputStream = object : SmartByteArrayOutputStream(bufferSize) {
        override fun close() {
            this@File.contents = data()
            super.close()
        }
    }
}