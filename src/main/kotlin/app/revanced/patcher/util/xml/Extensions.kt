package app.revanced.patcher.util.xml

import com.reandroid.xml.XMLDocument

internal fun XMLDocument.scanIdRegistrations() = sequence {
    val elements = mutableListOf(documentElement)
    while (elements.size != 0) {
        val current = elements.removeAt(elements.size - 1)
        yieldAll(current.listAttributes().filter { it.value.startsWith("@+id/") })
        elements.addAll(current.listChildElements())
    }
}