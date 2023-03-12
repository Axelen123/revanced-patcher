package app.revanced.patcher.apk

import app.revanced.patcher.util.InMemoryChannel
import com.reandroid.apk.ApkModule
import com.reandroid.apk.xmlencoder.EncodeMaterials
import com.reandroid.apk.xmlencoder.XMLEncodeSource
import com.reandroid.archive.ByteInputSource
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.common.Frameworks
import com.reandroid.common.TableEntryStore
import com.reandroid.xml.XMLDocument
import com.reandroid.xml.source.XMLDocumentSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.FileAttributeView
import java.nio.file.attribute.FileStoreAttributeView
import java.nio.file.spi.FileSystemProvider

fun unimplemented(): Nothing = throw Error("Not implemented.")

enum class PathKind {
    /**
     * Resource table (res/values/public.xml).
     */
    TABLE,

    /**
     * Resource (res/value*).
     */
    VALUE,

    /**
     * A file in the Apk archive.
     */
    ARCHIVE
}

class ARSCPath(internal val fs: ApkFileSystem, internal val filePath: String) : Path {
    private fun from(s: String) = ARSCPath(fs, s)
    private fun into() = File(filePath)
    override fun toString() = filePath

    // region Path implementation
    override fun compareTo(other: Path) = unimplemented()
    override fun register(
        p0: WatchService,
        p1: Array<out WatchEvent.Kind<*>>,
        vararg p2: WatchEvent.Modifier?
    ) = unimplemented()

    override fun getFileSystem() = fs
    override fun isAbsolute() = !startsWith(".")
    override fun getRoot() = null
    override fun getFileName() = from(filePath.split('/').last())
    override fun getParent() = from(into().parent)
    override fun getNameCount() = into().toPath().nameCount
    override fun getName(index: Int) = from(into().name)
    override fun subpath(start: Int, end: Int) = from(into().toPath().subpath(start, end).toString())
    override fun startsWith(other: Path) = filePath.startsWith(other.toString())
    override fun endsWith(other: Path) = filePath.endsWith(other.toString())
    override fun normalize() = from(into().normalize().toString())
    override fun resolve(other: Path) = from(into().resolve(other.toString()).toString())
    override fun relativize(other: Path) = from(into().toPath().relativize(other).toString())
    override fun toUri() = unimplemented()
    override fun toAbsolutePath() = this.also { if (!isAbsolute) unimplemented() }
    override fun toRealPath(vararg opts: LinkOption?) = null
    // endregion

    internal val kind
        get() = when {
            filePath == "res/values/public.xml" -> PathKind.TABLE
            filePath.startsWith("res/values") -> PathKind.VALUE
            else -> PathKind.ARCHIVE
        }
}

class ApkFileStore(private val apk: Apk) : FileStore() {
    override fun name() = apk.toString()
    override fun type() = apk.toString()
    override fun isReadOnly() = false
    override fun getTotalSpace() = -1L
    override fun getUsableSpace() = -1L
    override fun getUnallocatedSpace() = -1L
    override fun supportsFileAttributeView(view: Class<out FileAttributeView>?) = false
    override fun supportsFileAttributeView(name: String) = false
    override fun <V : FileStoreAttributeView?> getFileStoreAttributeView(type: Class<V>?) = null
    override fun getAttribute(name: String) = null
}

class ApkFileSystem(private val provider: ApkBundleFileSystemProvider, internal val apk: Apk) : FileSystem() {
    override fun close() {}
    override fun provider() = provider
    override fun isOpen() = true
    override fun isReadOnly() = false
    override fun getSeparator() = "/"
    override fun getRootDirectories() = listOf(ARSCPath(this, "")) // TODO: not sure if this will cause problems...
    override fun getFileStores() = listOf(ApkFileStore(apk))
    override fun supportedFileAttributeViews() = setOf<String>()
    override fun getPath(first: String, vararg more: String) = ARSCPath(this, listOf(first, *more).joinToString("/"))
    override fun getPathMatcher(str: String) = unimplemented()
    override fun getUserPrincipalLookupService() = null
    override fun newWatchService() = null
}

fun Path.assertARSC(): ARSCPath {
    if (this !is ARSCPath) {
        throw Error("Expected ARSCPath")
    }
    return this
}

class ApkBundleFileSystemProvider(internal val bundle: ApkBundle) : FileSystemProvider() {
    override fun getScheme() = "apk"
    override fun newFileSystem(uri: URI, env: MutableMap<String, *>?) = getFileSystem(uri)
    override fun getFileSystem(uri: URI) = ApkFileSystem(
        this,
        when (uri.path) {
            "language" -> bundle.split?.language
            "asset" -> bundle.split?.asset
            "library" -> bundle.split?.library
            else -> null
        } ?: bundle.base
    )

    override fun getPath(uri: URI) = unimplemented()
    override fun newByteChannel(
        path: Path,
        opts: MutableSet<out OpenOption>,
        vararg attrs: FileAttribute<*>
    ): SeekableByteChannel {
        val path = path.assertARSC()
        return when (path.kind) {
            PathKind.ARCHIVE -> ArchiveChannel(path.filePath, path.fs.apk.module)
            else -> unimplemented()
        }
    }

    override fun newDirectoryStream(dir: Path, filter: DirectoryStream.Filter<in Path>) = unimplemented()
    override fun createDirectory(dir: Path, vararg attrs: FileAttribute<*>) {
        dir.assertARSC().apply {
            when (kind) {
                PathKind.ARCHIVE -> fs.apk.module.apkArchive.add(ByteInputSource(ByteArray(0), dir.toString())) // TODO: test if this actually works (maybe ensure the path ends with "/"?)
                else -> unimplemented()
            }
        }
    }

    override fun delete(path: Path) {
        path.assertARSC().apply {
            when (kind) {
                PathKind.ARCHIVE -> fs.apk.module.apkArchive.remove(filePath)
                PathKind.VALUE -> unimplemented()
                PathKind.TABLE -> throw Error("Cannot delete the resource table.")
            }
        }
    }

    override fun copy(a: Path, b: Path, vararg opts: CopyOption?) = unimplemented()
    override fun move(a: Path, b: Path, vararg opts: CopyOption?) = unimplemented()
    override fun isSameFile(a: Path, b: Path) = a == b
    override fun isHidden(path: Path) = false
    override fun getFileStore(path: Path) = path.assertARSC().fs.fileStores[0]
    override fun checkAccess(path: Path?, vararg modes: AccessMode?) {}

    // TODO: maybe implement these?
    override fun <V : FileAttributeView?> getFileAttributeView(path: Path, type: Class<V>?, vararg opts: LinkOption?) =
        unimplemented()

    override fun <A : BasicFileAttributes?> readAttributes(path: Path?, type: Class<A>?, vararg opts: LinkOption?) =
        unimplemented()

    override fun readAttributes(path: Path, attrs: String, vararg opts: LinkOption?) = unimplemented()
    override fun setAttribute(path: Path?, attr: String?, value: Any?, vararg opts: LinkOption?) = unimplemented()

}

// TODO: deal with res/values/public.xml and everything in res/values* because they are not real

// TODO: deal with open options...
class ArchiveChannel(internal val path: String, private val module: ApkModule) : InMemoryChannel() {
    /*
    fun isDirectory() = path.endsWith('/')
    fun isFile() = !isDirectory()
     */

    private val isBinaryXml get() = path.endsWith(".xml")
    private var changed = false

    // TODO: move TableEntryStore somewhere more global.

    // TODO: Only decode when we actually need it.
    init {
        val inputSrc = module.apkArchive.getInputSource(path)

        write(ByteBuffer.wrap(if (isBinaryXml) {
            // Decode resource XML
            val resDoc = ResXmlDocument()
            // resDoc.setApkFile(...)
            inputSrc.openStream().use { resDoc.readBytes(it) }

            // Convert it to normal XML
            val entryStore = TableEntryStore()
            entryStore.add(Frameworks.getAndroid())
            entryStore.add(module.tableBlock)
            val xmlDoc = resDoc.decodeToXml(
                entryStore,
                module.listResFiles().find { it.filePath == path }!!.pickOne().packageBlock.id
            )

            ByteArrayOutputStream(512 * 1024).apply { xmlDoc.save(this, false) }.toByteArray()
        } else inputSrc.openStream().use { it.readAllBytes() })
        )
        position(0L)
    }

    override fun write(source: ByteBuffer) = super.write(source).also {
        changed = true
    }

    override fun close() {
        super.close()
        if (changed) {
            module.apkArchive.add(
                if (!isBinaryXml) ByteInputSource(contents, path) else {
                    // TODO: properly initialize EncodeMaterials
                    XMLEncodeSource(
                        EncodeMaterials(),
                        XMLDocumentSource(path, XMLDocument.load(ByteArrayInputStream(contents)))
                    )
                }
            )
        }
    }
    /*
    fun inputStream(): InputStream {
        val stream = module.apkArchive.getInputSource(path).openStream()
        if (!isBinaryXml) {
            return stream
        }

        // Decode resource XML
        val resDoc = ResXmlDocument()
        // resDoc.setApkFile(...)
        resDoc.readBytes(stream)

        // Convert it to normal XML
        val entryStore = TableEntryStore()
        entryStore.add(Frameworks.getAndroid())
        entryStore.add(module.tableBlock)
        val xmlDoc = resDoc.decodeToXml(
            entryStore,
            module.listResFiles().find { it.filePath == path }!!.pickOne().packageBlock.id
        )

        // Create an InputStream from it.
        val buf = ByteArrayOutputStream(512 * 1024)
        xmlDoc.save(buf, false)
        return ByteArrayInputStream(buf.toByteArray())
    }

    // TODO: deal with binary xml...
    fun outputStream(): OutputStream = ArchiveFileOutputStream(this)
    private fun replaceInputSource(src: InputSource) = module.apkArchive.add(src)

    class ArchiveFileOutputStream(private val parent: ArchiveFile) : OutputStream() {
        private val inner = ByteBuffer.allocate(512)
        override fun write(b: Int) {
            inner.putInt(b)
        }

        private fun toInputSource() = ByteInputSource(inner.array(), parent.path)
        override fun close() {
            // Replace the file in the archive
            parent.replaceInputSource(toInputSource())
        }
    }
     */

}