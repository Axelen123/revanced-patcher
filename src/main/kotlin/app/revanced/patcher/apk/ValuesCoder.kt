package app.revanced.patcher.apk

import com.reandroid.apk.XmlHelper
import com.reandroid.apk.xmldecoder.XMLBagDecoder
import com.reandroid.arsc.chunk.TypeBlock
import com.reandroid.arsc.value.*
import com.reandroid.xml.XMLAttribute
import com.reandroid.xml.XMLDocument
import com.reandroid.xml.XMLElement
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

internal class ValuesCoder(
    private val typeBlock: TypeBlock?,
    private val qualifiers: String,
    private val type: String,
    internal val apk: Apk
) :
    Coder {
    private val decodedEntries = HashMap<Int, Set<ResConfig>>()
    private val xmlBagDecoder = XMLBagDecoder(apk.entryStore)
    override fun exists() = typeBlock != null
    override fun decode(): ByteArray = ByteArrayOutputStream(256).also {
        (apk.getDelayed(qualifiers, type) ?: XMLDocument("resources").apply {
            typeBlock!!.listEntries(true).forEach { entry ->
                if (!containsDecodedEntry(entry)) {
                    documentElement.addChild(decodeValue(entry))
                }
            }

            if (documentElement.childesCount == 0) {
                return@also
            }
        }).save(it, false)
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
                    apk.entryStore, entry.packageBlock, resValue.valueType, resValue.data
                )
                element.textContent = value
            }
        } else {
            val mapEntry = entry.tableEntry as ResTableMapEntry
            xmlBagDecoder.decode(mapEntry, element)
        }
        return element
    }

    override fun encode(contents: ByteArray) {
        apk.encodeValues(qualifiers, type, XMLDocument.load(ByteArrayInputStream(contents)), true)
    }
}