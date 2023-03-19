package app.revanced.patcher.apk.arsc

import app.revanced.patcher.apk.Apk
import com.reandroid.apk.XmlHelper
import com.reandroid.apk.xmldecoder.XMLBagDecoder
import com.reandroid.apk.xmlencoder.EncodeMaterials
import com.reandroid.apk.xmlencoder.XMLEncodeSource
import com.reandroid.archive.APKArchive
import com.reandroid.archive.ByteInputSource
import com.reandroid.archive.InputSource
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.value.*
import com.reandroid.xml.XMLAttribute
import com.reandroid.xml.XMLDocument
import com.reandroid.xml.XMLElement
import com.reandroid.xml.source.XMLDocumentSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.io.path.Path
import kotlin.io.path.nameWithoutExtension

internal sealed interface FileBackend {
    fun load(): ByteArray
    fun save(contents: ByteArray)
    fun exists(): Boolean
}

/**
 * Represents a file in the [APKArchive].
 */
internal sealed class ArchiveBackend(
    private val path: String,
    private val key: Pair<String, String>?,
    protected val store: EncodeManager
) : FileBackend {
    private val archive: APKArchive = store.module.apkArchive
    protected val archivePath = store.resFileTable[path] ?: path
    protected val source: InputSource? = archive.getInputSource(archivePath)

    override fun exists() = source != null

    protected fun saveInputSource(src: InputSource) {
        archive.add(src)
        // Register the file in the resource table if needed.
        if (key != null) {
            val (qualifiers, type) = key
            val name = Path(path).nameWithoutExtension
            store.logger.error("Updating: $qualifiers , $type : $name")

            store.packageBlock!!.getOrCreate(
                qualifiers,
                type,
                name
            ).also {
                (it.tableEntry as ResTableEntry).value.valueAsString = archivePath
            }
            if (store.module.listResFiles().find { it.filePath == archivePath } == null) {
                throw Apk.ApkException.Encode("Failed to register resource: $archivePath")
            }
        }
    }

    /**
     * Loads/saves the raw contents of the file in the archive.
     */
    class Raw(path: String, key: Pair<String, String>?, store: EncodeManager) : ArchiveBackend(path, key, store) {
        override fun load(): ByteArray = ByteArrayOutputStream(source!!.length.toInt()).apply {
            source.openStream().use { it.copyTo(this) }
        }.toByteArray()

        override fun save(contents: ByteArray) = saveInputSource(ByteInputSource(contents, archivePath))
    }

    /**
     * Transparently decodes and encodes Android binary XML to/from regular XML.
     */
    class XML(path: String, key: Pair<String, String>?, store: EncodeManager) : ArchiveBackend(path, key, store) {
        private val isManifest = archivePath == Apk.MANIFEST_NAME

        override fun load(): ByteArray = ByteArrayOutputStream(4096).also {
            when {
                /**
                 * Avoid having to potentially encode and decode the same XML over and over again.
                 * This also means we do not have to deal with encoding references to resources that do not exist yet.
                 */
                source is XMLEncodeSource -> source.xmlSource.xmlDocument
                isManifest -> store.module.androidManifestBlock.decodeToXml(
                    store.entryStore,
                    store.packageBlock?.id ?: 0
                )

                else -> store.module.decodeXMLFile(archivePath)
            }.save(it, false)
        }.toByteArray()

        override fun save(contents: ByteArray) = saveInputSource(
            XMLEncodeSource(
                store.encodeMaterials,
                XMLDocumentSource(archivePath, XMLDocument.load(ByteArrayInputStream(contents)))
            )
        )
    }
}

internal class ValuesBackend(
    private val qualifiers: String,
    private val type: String,
    private val store: EncodeManager,
) : FileBackend {
    private val decodedEntries = HashMap<Int, Set<ResConfig>>()
    private val xmlBagDecoder = XMLBagDecoder(store.entryStore)

    override fun exists() = store.findTypeBlock(qualifiers, type) != null
    override fun load(): ByteArray = ByteArrayOutputStream(256).also {
        XMLDocument("resources").apply {
            store.packageBlock!!.getOrCreateSpecType(type).getOrCreateTypeBlock(qualifiers).listEntries(true)
                .forEach { entry ->
                    if (!containsDecodedEntry(entry)) {
                        documentElement.addChild(decodeValue(entry))
                    }
                }

            if (documentElement.childesCount == 0) {
                return@also
            }
            save(it, false)
        }
    }.toByteArray()

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
                    store.entryStore, entry.packageBlock, resValue.valueType, resValue.data
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
        store.valuesEncoder.encodeValuesXml(type, qualifiers, XMLDocument.load(ByteArrayInputStream(contents)))
}