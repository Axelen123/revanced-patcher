package app.revanced.patcher.apk

import com.reandroid.xml.XMLDocument
import com.reandroid.xml.XMLException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

class ResourceFile private constructor(
    private val handle: FileHandle,
    private val archive: Archive,
    private val resources: Apk.Resources,
    readResult: Archive.ReadResult?
) :
    Closeable {
    private var changed = false
    private val xml = readResult?.xml ?: handle.virtualPath.endsWith(".xml")

    internal constructor(handle: FileHandle, archive: Archive, resources: Apk.Resources) : this(
        handle,
        archive,
        resources,
        archive.read(resources, handle)
    )

    var contents = readResult?.data ?: ByteArray(0)
        set(value) {
            changed = true
            field = value
        }

    val exists = readResult != null

    override fun toString() = handle.virtualPath

    init {
        archive.lock(handle)
    }

    override fun close() {
        if (changed) {
            if (xml) archive.writeXml(
                resources,
                handle.archivePath,
                try {
                    XMLDocument.load(String(contents))
                } catch (e: XMLException) {
                    throw Apk.ApkException.Encode("Failed to parse XML while writing ${handle.virtualPath}", e)
                }
            ) else archive.writeRaw(handle.archivePath, contents)
        }
        handle.callback?.invoke()
        archive.unlock(handle)
    }

    fun readText() = String(contents)
    fun writeText(string: String) {
        contents = string.toByteArray()
    }

    fun inputStream(): InputStream = ByteArrayInputStream(contents)
    fun outputStream(bufferSize: Int = 8 * 1024): OutputStream =
        object : ByteArrayOutputStream(bufferSize) {
            override fun close() {
                this@ResourceFile.contents = if (buf.size > count) buf.copyOf(count) else buf
                super.close()
            }
        }
}