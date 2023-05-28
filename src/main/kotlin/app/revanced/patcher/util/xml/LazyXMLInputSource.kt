package app.revanced.patcher.util.xml

import app.revanced.patcher.apk.Apk
import app.revanced.patcher.resource.boolean
import com.reandroid.apk.xmlencoder.EncodeException
import com.reandroid.apk.xmlencoder.XMLEncodeSource
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.xml.XMLDocument
import com.reandroid.xml.XMLElement
import com.reandroid.xml.source.XMLDocumentSource

/**
 * Archive input source that lazily encodes the [XMLDocument] when you read from it.
 *
 * @param name The file name of this input source.
 * @param document The [XMLDocument] to encode.
 * @param resources The [Apk.ResourceContainer] to use for encoding.
 */
internal class LazyXMLInputSource(
    name: String,
    val document: XMLDocument,
    private val resources: Apk.ResourceContainer
) : XMLEncodeSource(resources.resourceTable.encodeMaterials, XMLDocumentSource(name, document)) {
    private fun XMLElement.registerIds() {
        listAttributes().forEach { attr ->
            if (attr.value.startsWith("@+id/")) {
                val name = attr.value.split('/').last()
                resources.set("id", name, boolean(false))
                attr.value = "@id/$name"
            }
        }

        listChildElements().forEach { it.registerIds() }
    }

    private var ready = false
    override fun getResXmlBlock(): ResXmlDocument {
        if (!ready) {
            throw Apk.ApkException.Encode("$name has not been encoded yet")
        }

        return super.getResXmlBlock()
    }

    /**
     * Encode the [XMLDocument] associated with this input source.
     */
    fun encode() {
        // Handle all @+id/id_name references in the document.
        document.documentElement.registerIds()

        ready = true

        try {
            // This will call XMLEncodeSource.getResXmlBlock(), which will encode the document if it has not already been encoded.
            resXmlBlock
        } catch (e: EncodeException) {
            throw Apk.ApkException.Encode("Failed to encode $name", e)
        }
    }
}