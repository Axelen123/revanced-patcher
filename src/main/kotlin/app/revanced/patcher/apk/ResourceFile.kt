package app.revanced.patcher.apk

import app.revanced.patcher.arsc.ResourceFileImpl
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

class ResourceFile internal constructor(private val path: String, private val apk: Apk, private val backend: ResourceFileImpl) :
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
            outputStream().use { backend.load(it) }
            changed = false
        }
    }

    override fun close() {
        if (changed) {
            backend.save(contents)
        }
        apk.unlockFile(path)
    }

    fun readText() = String(contents)
    fun writeText(string: String) {
        contents = string.toByteArray()
    }

    fun inputStream(): InputStream = ByteArrayInputStream(contents)
    fun outputStream(bufferSize: Int = backend.suggestedSize()): OutputStream =
        object : ByteArrayOutputStream(bufferSize) {
            override fun close() {
                this@ResourceFile.contents = if (buf.size > count) buf.copyOf(count) else buf
                super.close()
            }
        }
}