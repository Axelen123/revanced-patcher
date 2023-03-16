package app.revanced.patcher.apk

import app.revanced.patcher.logging.Logger
import app.revanced.patcher.util.InMemoryChannel
import com.reandroid.apk.xmlencoder.XMLEncodeSource
import com.reandroid.archive.ByteInputSource
import com.reandroid.archive.InputSource
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.TypeBlock
import com.reandroid.arsc.value.ResTableEntry
import com.reandroid.arsc.value.ResValue
import com.reandroid.common.EntryStore
import com.reandroid.common.TableEntryStore
import com.reandroid.xml.XMLDocument
import com.reandroid.xml.source.XMLDocumentSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.io.path.Path
import kotlin.io.path.nameWithoutExtension
import kotlin.reflect.jvm.jvmName

internal sealed interface Coder {
    fun decode(): ByteArray
    fun encode(contents: ByteArray)
    fun exists(): Boolean
}

class File internal constructor(private val path: String, private val apk: Apk, private val coder: Coder) :
    Closeable {
    internal val channel = InMemoryChannel()
    internal var changed = false
    val exists = coder.exists()

    override fun toString() = path

    init {
        apk.lockFile(path)
        if (exists) {
            apk.logger.info("Decoding file: $path")
            replaceContents(coder.decode())
            changed = false
        }
    }

    override fun close() {
        if (changed) {
            apk.logger.info("Encoding file: $path")
            coder.encode(contents)
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

internal class ArchiveCoder(private val path: String, private val apk: Apk) : Coder {
    private val archivePath = apk.resFileTable?.get(path) ?: path
    private val isBinaryXml get() = archivePath.endsWith(".xml") // TODO: figure out why tf get() is needed.
    private val module = apk.module
    private val archive = module.apkArchive
    private val source: InputSource? = archive.getInputSource(archivePath)

    override fun decode(): ByteArray = if (isBinaryXml) {
        /**
         * Avoid having to potentially encode and decode the same XML over and over again.
         * This also means we avoid having to encode XML potentially referencing resources that have not been created yet.
         */
        val xml = if (source is XMLEncodeSource) source.xmlSource.xmlDocument else module.decodeXMLFile(archivePath)
        ByteArrayOutputStream().also {
            xml.save(it, false)
        }.toByteArray()
    } else source!!.openStream().use { it.readAllBytes() }

    /*
    private fun getTypeBlock() {
        val qualifiers = path.split("/")[1].split("-").toMutableList()
        val type = qualifiers.removeAt(0)
        if (qualifiers.size > 0) {
            qualifiers.add(0, "")
        }
        val key = "values${qualifiers.joinToString("-")}/${type}s"
        return apk.typeTable[key] ?:
    }
     */

    override fun encode(contents: ByteArray) {
        val needsRegistration = path.startsWith("res") && !exists()
        if (needsRegistration) {
            val qualifiers = path.split("/")[1].split("-").toMutableList()
            val type = qualifiers.removeAt(0)
            if (qualifiers.size > 0) {
                qualifiers.add(0, "")
            }
            
            apk.packageBlock!!.getOrCreate(qualifiers.joinToString("-"), type, Path(path).nameWithoutExtension).also {
                // it.setValueAsString(path) does not work for some reason...
                (it.tableEntry as ResTableEntry).value.valueAsString = path
            }
        }
        archive.add(
            if (!isBinaryXml) ByteInputSource(contents, archivePath) else {
                XMLEncodeSource(
                    apk.encodeMaterials,
                    XMLDocumentSource(archivePath, XMLDocument.load(ByteArrayInputStream(contents)))
                )
            }
        )
    }

    override fun exists() = source != null
}