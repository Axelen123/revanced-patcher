package app.revanced.patcher.apk

import app.revanced.patcher.apk.arsc.FileBackend
import app.revanced.patcher.util.InMemoryChannel
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.OutputStream
import java.nio.ByteBuffer

class File internal constructor(private val path: String, private val apk: Apk, private val backend: FileBackend) :
    Closeable {
    internal val channel = InMemoryChannel()
    internal var changed = false
    val exists = backend.exists()

    override fun toString() = path

    init {
        apk.lockFile(path)
        if (exists) {
            apk.logger.info("Decoding file: $path")
            replaceContents(backend.load())
            changed = false
        }
    }

    override fun close() {
        if (changed) {
            apk.logger.info("Encoding file: $path")
            backend.save(contents)
        }
        apk.unlockFile(path)
    }

    val contents get() = channel.contents
    fun readText() = String(contents)
    fun replaceContents(buf: ByteArray) {
        changed = true
        channel.contents = buf
        channel.position = 0
    }

    fun inputStream() = ByteArrayInputStream(contents)
    fun outputStream() = FileOutputStream(this)

    class FileOutputStream(private val file: File) : OutputStream() {
        init {
            file.channel.truncate(0L)
            file.changed = true
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            file.channel.write(ByteBuffer.wrap(if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len)))
        }

        override fun write(i: Int) {
            file.channel.write(ByteBuffer.wrap(byteArrayOf(i.toByte())))
        }
    }
}