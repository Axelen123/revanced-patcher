package app.revanced.patcher.arsc

import app.revanced.patcher.apk.Apk
import com.reandroid.apk.ApkModule
import com.reandroid.apk.xmlencoder.EncodeUtil
import com.reandroid.archive.APKArchive
import com.reandroid.archive.ByteInputSource
import com.reandroid.archive.InputSource
import com.reandroid.arsc.value.ResConfig
import com.reandroid.xml.XMLDocument
import java.io.File
import java.io.OutputStream

// TODO: delete the interface and use ArchiveBackend directly instead.
internal sealed interface FileBackend {
    fun load(outputStream: OutputStream)
    fun save(contents: ByteArray)
    fun exists(): Boolean
    fun suggestedSize() = 8 * 1024
}

/**
 * Represents a file in the [APKArchive].
 */
internal sealed class ArchiveBackend(
    path: String,
    protected val resources: Apk.Resources,
    private val archive: APKArchive,
) : FileBackend {
    data class RegistrationData(val qualifiers: String, val type: String, val name: String)

    private val registration: RegistrationData?

    /**
     * Maps the "virtual" name to the "archive" name using the resource table.
     * This is required because the file name developers use might not correspond to the name in the archive.
     * Example: res/drawable-hdpi/icon.png -> res/4a.png
     */
    protected val archivePath =
        if (resources.pkg != null && path.startsWith("res/") && path.count { it == '/' } == 2) {
            registration = File(path).let {
                RegistrationData(
                    EncodeUtil.getQualifiersFromResFile(it),
                    EncodeUtil.getTypeNameFromResFile(it),
                    it.nameWithoutExtension
                )
            }

            with(registration) {
                val resConfig = ResConfig.parse(qualifiers)
                resources.tableBlock.resolveReference(resources.pkg.getResourceId(type, name))
                    .single { it.resConfig == resConfig }.resValue.valueAsString
            } ?: path
        } else {
            registration = null
            path
        }

    protected val source: InputSource? = archive.getInputSource(archivePath)

    override fun exists() = source != null

    protected fun saveInputSource(src: InputSource) {
        archive.add(src)
        // Register the file in the resource table if needed.
        registration?.let {
            resources.packageBlock.getOrCreate(
                it.qualifiers,
                it.type,
                it.name
            ).resValue.valueAsString = archivePath
        }
    }

    /**
     * Loads/saves the raw contents of the file in the archive.
     */
    class Raw(path: String, resources: Apk.Resources, archive: APKArchive) :
        ArchiveBackend(path, resources, archive) {
        override fun load(outputStream: OutputStream) {
            source!!.write(outputStream)
        }

        override fun save(contents: ByteArray) = saveInputSource(ByteInputSource(contents, archivePath))
        override fun suggestedSize() = source?.length?.toInt() ?: super.suggestedSize()
    }

    /**
     * Transparently decodes and encodes Android binary XML to/from regular XML.
     */
    class XML(
        path: String,
        resources: Apk.Resources,
        private val module: ApkModule,
    ) : ArchiveBackend(path, resources, module.apkArchive) {
        override fun load(outputStream: OutputStream) {
            when (source) {
                /**
                 * Avoid having to potentially encode and decode the same XML over and over again.
                 * This also means we do not have to deal with encoding references to resources that do not exist yet.
                 */
                is LazyXMLInputSource -> source.document
                else -> module.loadResXmlDocument(archivePath)
                    .decodeToXml(resources.entryStore, if (resources.hasResourceTable) resources.packageBlock.id else 0)
            }.save(outputStream, false)
        }

        override fun save(contents: ByteArray) = saveInputSource(
            LazyXMLInputSource(
                archivePath,
                XMLDocument.load(String(contents)),
                resources.encodeMaterials
            )
        )
    }
}
