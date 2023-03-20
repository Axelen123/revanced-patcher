package app.revanced.patcher.apk.arsc

import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TypeBlock

/**
 * Used for finding resource IDs.
 */
class ResourceMapper(packageBlock: PackageBlock) {
    class NotFoundException(val type: String, val name: String) : Exception() {
        override fun toString() = "Could not find ID for resource \"${name}\" of type \"${type}\""
    }

    internal val types = packageBlock.listAllSpecTypePair().associateBy { it.typeName }

    private fun TypeBlock.findId(name: String) = listEntries(true).find { it.name == name }?.resourceId?.toLong()

    fun find(type: String, name: String) = run {
        val spec = types[type] ?: return@run null

        // Generally a good first candidate.
        val typeBlock = spec.getTypeBlock("") ?: spec.listTypeBlocks().firstOrNull() ?: return@run null
        return@run typeBlock.findId(name) ?: spec.listTypeBlocks().firstNotNullOfOrNull { it.findId(name) }
    } ?: throw NotFoundException(type, name)
}