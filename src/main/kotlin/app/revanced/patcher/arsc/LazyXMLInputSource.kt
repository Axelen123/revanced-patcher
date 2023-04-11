package app.revanced.patcher.arsc

import com.reandroid.apk.xmlencoder.EncodeMaterials
import com.reandroid.apk.xmlencoder.XMLEncodeSource
import com.reandroid.xml.XMLDocument
import com.reandroid.xml.source.XMLDocumentSource

internal class LazyXMLInputSource(name: String, val document: XMLDocument, materials: EncodeMaterials) : XMLEncodeSource(materials, XMLDocumentSource(name, document)) {
    fun encode() {
        resXmlBlock
    }
}