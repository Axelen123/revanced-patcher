package app.revanced.patcher.apk

import app.revanced.patcher.logging.Logger
import app.revanced.patcher.util.InMemoryChannel
import com.reandroid.apk.xmlencoder.XMLEncodeSource
import com.reandroid.archive.ByteInputSource
import com.reandroid.xml.XMLDocument
import com.reandroid.xml.source.XMLDocumentSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.OutputStream
import java.nio.ByteBuffer

internal sealed interface Coder {
    fun decode(): ByteArray
    fun encode(contents: ByteArray)
    fun exists(): Boolean
}

class File internal constructor(private val path: String, private val logger: Logger, private val coder: Coder) :
    Closeable {
    internal val channel = InMemoryChannel()
    internal var changed = false
    val exists = coder.exists()

    override fun toString() = path

    init {
        if (exists) {
            logger.info("Decoding file: $path")
            replaceContents(coder.decode())
            changed = false
        }
    }

    override fun close() {
        if (changed) {
            logger.info("Encoding file: $path")
            coder.encode(contents)
        }
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

internal class ArchiveCoder(val path: String, private val apk: Apk) : Coder {
    private val archivePath = apk.resFileTable?.get(path) ?: path
    private val isBinaryXml get() = archivePath.endsWith(".xml") // TODO: figure out why tf get() is needed.
    private val module = apk.module
    private val archive = module.apkArchive
    override fun decode(): ByteArray {
        apk.logger.warn("explosion: $archivePath : $path")
        val inputSrc = archive.getInputSource(archivePath)!! // TODO: figure out why this does not work twice...
        return if (isBinaryXml) {
            ByteArrayOutputStream().apply {
                // Decode android binary XML and then convert it to normal XML
                module.loadResXmlDocument(archivePath).decodeToXml(
                    apk.entryStore,
                    apk.packageBlock!!.id
                ).save(this, false)
            }.toByteArray()
        } else inputSrc.openStream().use { it.readAllBytes() }
    }

    override fun encode(contents: ByteArray) {
        // TODO: register new files in the resource table.
        archive.apply {
            remove(archivePath)
            add(
                if (!isBinaryXml) ByteInputSource(contents, archivePath) else {
                    XMLEncodeSource(
                        apk.encodeMaterials,
                        XMLDocumentSource(archivePath, XMLDocument.load(ByteArrayInputStream(contents)))
                    )
                }
            )
        }
    }

    override fun exists() = apk.module.apkArchive.getInputSource(archivePath) != null
}