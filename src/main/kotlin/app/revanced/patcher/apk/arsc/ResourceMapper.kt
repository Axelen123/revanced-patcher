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

    /**
     * Note that this can have over 20000 entries with some apps!
     * TODO: consider replacing with a fuzzy version of find(type, name)
     */
    /*
    private val nameLookupTable by lazy {
        HashMap<String, Pair<Long, Boolean>>().also { map ->
            packageBlock.listAllSpecTypePair().flatMap { it.listTypeBlocks() }.flatMap { it.listEntries(true) }
                .forEach {
                    val current = map[it.name]
                    map[it.name] = if (current != null) {
                        // Mark it as having multiple entries with the same name by making it negative.
                        current.first to true
                    } else it.resourceId.toLong() to false
                }
        }
    }

    fun find(name: String) = nameLookupTable[name]?.first

    fun single(name: String) = nameLookupTable[name]?.run {
        if (second) {
            // If there are multiple entries with the same name, throw an exception because we can't know which one to use.
            throw Error("$name has multiple entries.")
        }
        first
    }*/
}