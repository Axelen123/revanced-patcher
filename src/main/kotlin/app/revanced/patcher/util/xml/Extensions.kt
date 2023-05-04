package app.revanced.patcher.util.xml

import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.xml.XMLDocument
import com.reandroid.xml.XMLElement

private fun XMLElement.registerIds(
    packageBlock: PackageBlock
) {
    listAttributes().forEach { attr ->
        if (attr.value.startsWith("@+id/")) {
            val name = attr.value.split('/').last()
            packageBlock.getOrCreate("", "id", name).resValue.valueAsBoolean = false
            attr.value = "@id/$name"
        }
    }

    listChildElements().forEach { it.registerIds(packageBlock) }
}

/**
 * Register all "@+id/id_name" references in the [XMLDocument].
 */
internal fun XMLDocument.registerIds(packageBlock: PackageBlock) = documentElement.registerIds(packageBlock)