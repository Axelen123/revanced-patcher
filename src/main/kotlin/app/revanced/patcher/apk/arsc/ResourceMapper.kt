package app.revanced.patcher.apk.arsc

import app.revanced.patcher.patch.PatchResult
import com.reandroid.arsc.chunk.PackageBlock

/**
 * Used for finding resource IDs.
 */
class ResourceMapper(private val packageBlock: PackageBlock) {
    fun find(type: String, name: String) = packageBlock.typeBlocksFor(type).firstNotNullOfOrNull { it.findEntry(name) }?.resourceId?.toLong() ?: throw PatchResult.Error("Could not find resource \"${name}\" of type \"${type}\"")
}