package app.revanced.patcher.util.xml

import app.revanced.patcher.apk.Apk
import com.reandroid.apk.xmlencoder.EncodeException
import com.reandroid.apk.xmlencoder.EncodeMaterials
import com.reandroid.apk.xmlencoder.XMLEncodeSource
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.xml.XMLDocument
import com.reandroid.xml.XMLElement
import com.reandroid.xml.source.XMLDocumentSource

/**
 * Archive input source that lazily encodes the [XMLDocument] when you read from it.
 *
 * @param name The file name of this input source.
 * @param document The [XMLDocument] to encode.
 * @param materials The [EncodeMaterials] to use when encoding the document.
 */
internal class LazyXMLInputSource(name: String, private val document: XMLDocument, private val materials: EncodeMaterials) : XMLEncodeSource(materials, XMLDocumentSource(name, document)) {
    private fun XMLElement.registerIds(
        packageBlock: PackageBlock
    ) {
        listAttributes().forEach { attr ->
            if (attr.value.startsWith("@+id/")) {
                val name = attr.value.split('/').last()
                packageBlock.getOrCreate("", "id", name).resValue.valueAsBoolean = false
                attr.value = "@id/$name"
            }
        }

        listChildElements().forEach { it.registerIds(packageBlock) }
    }

    /**
     * Encode the [XMLDocument] associated with this input source.
     */
    fun encode() {
        // Handle all @+id/id_name references in the document.
        document.documentElement.registerIds(materials.currentPackage)

        try {
            // This will call XMLEncodeSource.getResXmlBlock(), which will encode the document if it has not already been encoded.
            resXmlBlock
        } catch (e: EncodeException) {
            throw Apk.ApkException.Encode("Failed to encode $name", e)
        }
    }
}