package app.revanced.patcher.apk.arsc

import app.revanced.patcher.patch.PatchResult
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TypeBlock

/**
 * Used for finding resource IDs.
 */
class ResourceMapper(packageBlock: PackageBlock) {
    internal val types = packageBlock.listAllSpecTypePair().associateBy {
        it.removeNullEntries(0)
        it.removeEmptyTypeBlocks()
        it.sortTypes()

        it.typeName
    }

    private fun TypeBlock.findId(name: String) = listEntries().find { it.name == name }?.resourceId?.toLong()

    fun find(type: String, name: String) =
        types[type]?.listTypeBlocks()?.firstNotNullOfOrNull { it.findId(name) }
            ?: throw PatchResult.Error("Could not find resource \"${name}\" of type \"${type}\"")
}