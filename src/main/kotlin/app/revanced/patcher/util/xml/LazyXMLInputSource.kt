package app.revanced.patcher.util.xml

import com.reandroid.apk.xmlencoder.EncodeMaterials
import com.reandroid.apk.xmlencoder.XMLEncodeSource
import com.reandroid.xml.XMLDocument
import com.reandroid.xml.source.XMLDocumentSource

/**
 * Archive input source that lazily encodes the [XMLDocument] when you read from it.
 *
 * @param name The file name of this input source.
 * @param document The [XMLDocument] to encode.
 * @param materials The [EncodeMaterials] to use when encoding the document.
 */
internal class LazyXMLInputSource(name: String, val document: XMLDocument, materials: EncodeMaterials) : XMLEncodeSource(materials, XMLDocumentSource(name, document)) {
    /**
     * Encode the [XMLDocument] associated with this input source.
     */
    fun encode() {
        // This will call XMLEncodeSource.getResXmlBlock(), which will encode the document if it has not already been encoded.
        resXmlBlock
    }
}