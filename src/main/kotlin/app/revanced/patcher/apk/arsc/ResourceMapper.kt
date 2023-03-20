package app.revanced.patcher.apk.arsc

import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TypeBlock

/**
 * Used for finding resource IDs.
 */
class ResourceMapper(packageBlock: PackageBlock) {
    class ResourceNotFoundException(val type: String, val name: String) : Exception() {
        override fun toString() = "Could not find ID for resource \"${name}\" of type \"${type}\""
    }

    internal val types = packageBlock.listAllSpecTypePair().associateBy {
        it.removeNullEntries(0)
        it.removeEmptyTypeBlocks()
        it.sortTypes()

        it.typeName
    }

    private fun TypeBlock.findId(name: String) = listEntries().find { it.name == name }?.resourceId?.toLong()

    fun find(type: String, name: String) =
        types[type]?.listTypeBlocks()?.firstNotNullOfOrNull { it.findId(name) } ?: throw ResourceNotFoundException(type, name)
}