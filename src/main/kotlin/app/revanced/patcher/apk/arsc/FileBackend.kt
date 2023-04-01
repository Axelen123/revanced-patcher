package app.revanced.patcher.apk.arsc

import app.revanced.patcher.apk.Apk
import com.reandroid.apk.ApkModule
import com.reandroid.apk.XmlHelper
import com.reandroid.apk.xmldecoder.XMLBagDecoder
import com.reandroid.apk.xmlencoder.EncodeUtil
import com.reandroid.apk.xmlencoder.XMLEncodeSource
import com.reandroid.archive.APKArchive
import com.reandroid.archive.ByteInputSource
import com.reandroid.archive.InputSource
import com.reandroid.arsc.value.*
import com.reandroid.xml.XMLAttribute
import com.reandroid.xml.XMLDocument
import com.reandroid.xml.XMLElement
import com.reandroid.xml.source.XMLDocumentSource
import java.io.ByteArrayInputStream
import java.io.File
import java.io.OutputStream

const val DEFAULT_BUFFER_SIZE = 8 * 1024

internal sealed interface FileBackend {
    fun load(outputStream: OutputStream)
    fun save(contents: ByteArray)
    fun exists(): Boolean
    fun suggestedSize() = DEFAULT_BUFFER_SIZE
}

/**
 * Represents a file in the [APKArchive].
 */
internal sealed class ArchiveBackend(
    private val path: String,
    protected val resources: Apk.Resources,
    private val archive: APKArchive,
) : FileBackend {
    data class RegistrationData(val qualifiers: String, val type: String, val name: String)

    private var registration: RegistrationData? = null

    /**
     * Maps the "virtual" name to the "archive" name using the resource table.
     * This is required because the file name developers use might not correspond to the name in the archive.
     * Example: res/drawable-hdpi/icon.png -> res/4a.png
     */
    protected val archivePath = run {
        if (!resources.hasResourceTable || !path.startsWith("res") || path.count { it == '/' } != 2) {
            return@run path
        }

        val file = File(path)
        registration = RegistrationData(
            EncodeUtil.getQualifiersFromResFile(file),
            EncodeUtil.getTypeNameFromResFile(file),
            file.nameWithoutExtension
        )

        with(registration!!) {
            resources.packageBlock.typeBlocksFor(
                qualifiers,
                type
            ).firstNotNullOfOrNull {
                it.findEntry(name)?.value { res ->
                    res.valueAsString
                }
            } ?: path
        }
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
            ).value { res ->
                res.valueAsString = archivePath
            }
        }
    }

    /**
     * Loads/saves the raw contents of the file in the archive.
     */
    class Raw(path: String, resources: Apk.Resources, archive: APKArchive) :
        ArchiveBackend(path, resources, archive) {
        override fun load(outputStream: OutputStream) {
            source!!.openStream().use { it.copyTo(outputStream, DEFAULT_BUFFER_SIZE) }
        }

        override fun save(contents: ByteArray) = saveInputSource(ByteInputSource(contents, archivePath))
        override fun suggestedSize() = source?.length?.toInt() ?: DEFAULT_BUFFER_SIZE

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
                is XMLEncodeSource -> source.xmlSource.xmlDocument
                else -> module.loadResXmlDocument(archivePath)
                    .decodeToXml(resources.entryStore, if (resources.hasResourceTable) resources.packageBlock.id else 0)
            }.save(outputStream, false)
        }

        override fun save(contents: ByteArray) = saveInputSource(
            XMLEncodeSource(
                resources.encodeMaterials,
                XMLDocumentSource(archivePath, XMLDocument.load(ByteArrayInputStream(contents)))
            )
        )
    }
}

internal class ValuesBackend(file: File, private val resources: Apk.Resources) : FileBackend {
    init {
        if (file.name == "public.xml") throw Apk.ApkException.Decode("Opening the resource id table is not supported.")
    }

    private val qualifiers = EncodeUtil.getQualifiersFromValuesXml(file)
    private val type = EncodeUtil.getTypeNameFromValuesXml(file)
    private val decodedEntries = HashMap<Int, Set<ResConfig>>()
    private val xmlBagDecoder = XMLBagDecoder(resources.entryStore)
    private val typeBlocks = resources.packageBlock.typeBlocksFor(qualifiers, type)

    override fun exists() = typeBlocks.any()

    override fun load(outputStream: OutputStream) {
        XMLDocument("resources").apply {
            typeBlocks.flatMap { it.entryArray.asSequence() }.forEach { entry ->
                if (!containsDecodedEntry(entry)) {
                    documentElement.addChild(decodeValue(entry))
                }
            }

            save(outputStream, false)
        }
    }

    private fun containsDecodedEntry(entry: Entry): Boolean {
        val resConfigSet = decodedEntries[entry.resourceId] ?: return false
        return resConfigSet.contains(entry.resConfig)
    }

    private fun decodeValue(entry: Entry): XMLElement {
        val element = XMLElement(XmlHelper.toXMLTagName(entry.typeName))
        val resourceId = entry.resourceId
        val attribute = XMLAttribute("name", entry.name)
        element.addAttribute(attribute)
        attribute.nameId = resourceId
        element.resourceId = resourceId
        if (!entry.isComplex) {
            val resValue = entry.tableEntry.value as ResValue
            if (resValue.valueType == ValueType.STRING) {
                XmlHelper.setTextContent(
                    element, resValue.dataAsPoolString
                )
            } else {
                val value = com.reandroid.arsc.decoder.ValueDecoder.decodeEntryValue(
                    resources.entryStore, entry.packageBlock, resValue.valueType, resValue.data
                )
                element.textContent = value
            }
        } else {
            val mapEntry = entry.tableEntry as ResTableMapEntry
            xmlBagDecoder.decode(mapEntry, element)
        }
        return element
    }

    override fun save(contents: ByteArray) =
        resources.valuesEncoder.encodeValuesXml(type, qualifiers, XMLDocument.load(ByteArrayInputStream(contents)))
}