package app.revanced.patcher.apk.arsc

import com.reandroid.arsc.base.Block
import com.reandroid.arsc.base.BlockArray
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TypeBlock
import com.reandroid.arsc.value.Entry
import com.reandroid.arsc.value.ResTableEntry
import com.reandroid.arsc.value.ResValue
import com.reandroid.arsc.value.TableEntry
import com.reandroid.xml.XMLDocument


internal fun <T : Block> BlockArray<T>.asSequence() = Sequence { iterator(true) }

internal fun PackageBlock.specTypePairsFor(type: String) = specTypePairArray.asSequence().filter { it.typeName == type }

internal fun PackageBlock.typeBlocksFor(type: String) = specTypePairsFor(type).flatMap {
    it.typeBlockArray.asSequence()
}

internal fun PackageBlock.typeBlocksFor(qualifiers: String, type: String) =
    typeBlocksFor(type).filter { it.qualifiers == qualifiers }

internal fun TypeBlock.findEntry(name: String) = entryArray.asSequence().find { it.name == name }

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