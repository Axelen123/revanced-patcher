package app.revanced.patcher.apk

import app.revanced.patcher.util.InMemoryChannel
import com.reandroid.apk.xmlencoder.XMLEncodeSource
import com.reandroid.archive.ByteInputSource
import com.reandroid.arsc.chunk.TypeBlock
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.xml.XMLDocument
import com.reandroid.xml.source.XMLDocumentSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.OutputStream
import java.nio.ByteBuffer

sealed interface Coder {
    fun decode(): ByteArray
    fun encode(contents: ByteArray)
    fun exists(): Boolean
}

class File(internal val coder: Coder) : Closeable {
    internal val channel = InMemoryChannel()
    internal var changed = false
    val exists = coder.exists()

    override fun toString() = coder.toString()

    init {
        if (exists) {
            replaceContents(coder.decode())
            changed = false
        }
    }

    override fun close() {
        if (changed) {
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

internal class ArchiveCoder(internal val path: String, internal val apk: Apk) : Coder {
    private val resFile = apk.resFileTable[path]
    private val archivePath = resFile?.filePath ?: path
    private val isBinaryXml get() = archivePath.endsWith(".xml") // TODO: figure out why tf get() is needed.
    override fun toString() = path

    override fun decode(): ByteArray {
        apk.logger.info("Decoding archive file: $path")

        val inputSrc = apk.module.apkArchive.getInputSource(archivePath)!!
        // fs.apk.logger.info("is binary XML: $isBinaryXml, $path, ${file.path.endsWith(".xml")}")
        return if (isBinaryXml) {
            // Decode resource XML
            val resDoc = ResXmlDocument()
            inputSrc.openStream().use { resDoc.readBytes(it) }

            val packageBlock = resFile?.pickOne()?.packageBlock
                ?: apk.module.tableBlock.packageArray.pickOne()
            // Convert it to normal XML
            val xmlDoc = resDoc.decodeToXml(
                apk.entryStore,
                packageBlock.id
            )

            return ByteArrayOutputStream().apply { xmlDoc.save(this, false) }.toByteArray()
        } else inputSrc.openStream().use { it.readAllBytes() }
    }

    override fun encode(contents: ByteArray) {
        apk.logger.info("Encoding archive file: $path")
        apk.module.apkArchive.add(
            if (!isBinaryXml) ByteInputSource(contents, archivePath) else {
                XMLEncodeSource(
                    apk.encodeMaterials,
                    XMLDocumentSource(archivePath, XMLDocument.load(ByteArrayInputStream(contents)))
                )
            }
        )
    }

    override fun exists() = apk.module.apkArchive.getInputSource(archivePath) != null
}