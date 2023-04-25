package app.revanced.patcher.arsc

import app.revanced.patcher.apk.Apk
import com.reandroid.apk.ApkModule
import com.reandroid.archive.APKArchive
import com.reandroid.archive.ByteInputSource
import com.reandroid.archive.InputSource
import com.reandroid.xml.XMLDocument
import java.io.OutputStream

/**
 * Represents a file in the [APKArchive].
 */
internal sealed class ResourceFileImpl(
    protected val path: String,
    private val archive: APKArchive,
    private val onSave: (() -> Unit)?
) {
    protected val source: InputSource? = archive.getInputSource(path)

    fun exists() = source != null
    open fun suggestedSize() = 8 * 1024
    abstract fun load(outputStream: OutputStream)
    abstract fun save(contents: ByteArray)

    protected fun saveInputSource(src: InputSource) {
        archive.add(src)
        onSave?.invoke()
    }

    /**
     * Loads/saves the raw contents of the file in the archive.
     */
    class Raw(path: String, archive: APKArchive, onSave: (() -> Unit)? = null) :
        ResourceFileImpl(path, archive, onSave) {
        override fun load(outputStream: OutputStream) {
            source!!.write(outputStream)
        }

        override fun save(contents: ByteArray) = saveInputSource(ByteInputSource(contents, path))
        override fun suggestedSize() = source?.length?.toInt() ?: super.suggestedSize()
    }

    /**
     * Transparently decodes and encodes Android binary XML to/from regular XML.
     */
    class XML(
        path: String,
        private val resources: Apk.Resources,
        private val module: ApkModule,
        onSave: (() -> Unit)? = null
    ) : ResourceFileImpl(path, module.apkArchive, onSave) {
        override fun load(outputStream: OutputStream) {
            when (source) {
                /**
                 * Avoid having to potentially encode and decode the same XML over and over again.
                 * This also means we do not have to deal with encoding references to resources that do not exist yet.
                 */
                is LazyXMLInputSource -> source.document
                else -> module.loadResXmlDocument(source).decodeToXml(resources.global.entryStore, resources.packageBlock?.id ?: 0)
            }.save(outputStream, false)
        }

        override fun save(contents: ByteArray) = saveInputSource(
            LazyXMLInputSource(
                path,
                XMLDocument.load(String(contents)),
                resources.global.encodeMaterials
            )
        )
    }
}
