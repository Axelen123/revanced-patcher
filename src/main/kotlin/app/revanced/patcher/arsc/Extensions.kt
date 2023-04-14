package app.revanced.patcher.arsc

import com.reandroid.arsc.value.Entry
import com.reandroid.arsc.value.ResTableEntry
import com.reandroid.arsc.value.ResValue
import com.reandroid.xml.XMLDocument

internal fun <T> Entry.value(callback: (ResValue) -> T): T? =
    if (tableEntry is ResTableEntry) callback(tableEntry.value as ResValue) else null

internal fun XMLDocument.scanIdRegistrations() = sequence {
    val elements = mutableListOf(documentElement)
    while (elements.size != 0) {
        val current = elements.removeAt(elements.size - 1)
        yieldAll(current.listAttributes().filter { it.value.startsWith("@+id/") })
        elements.addAll(current.listChildElements())
    }
}