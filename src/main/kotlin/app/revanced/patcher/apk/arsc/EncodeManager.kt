package app.revanced.patcher.apk.arsc

import app.revanced.patcher.logging.Logger
import com.reandroid.apk.AndroidFrameworks
import com.reandroid.apk.ApkModule
import com.reandroid.apk.ResourceIds
import com.reandroid.apk.xmlencoder.EncodeMaterials
import com.reandroid.apk.xmlencoder.ValuesEncoder
import com.reandroid.apk.xmlencoder.XMLEncodeSource
import com.reandroid.apk.xmlencoder.XMLFileEncoder
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.util.FrameworkTable
import com.reandroid.arsc.value.ResTableEntry
import com.reandroid.common.TableEntryStore
import com.reandroid.xml.XMLDocument
import java.io.ByteArrayInputStream

data class ResourceElement(val type: String, val name: String, val id: Long)

val frameworkTable: FrameworkTable = AndroidFrameworks.getLatest().tableBlock
val manifestEncodeMaterials = EncodeMaterials().apply {
    val tableBlock = TableBlock()
    tableBlock.packageArray.add(PackageBlock())
    currentPackage = tableBlock.pickOne()
    addFramework(frameworkTable)
}

internal fun XMLDocument.scanIdRegistrations() = sequence {
    val elements = mutableListOf(documentElement)
    while (elements.size != 0) {
        val current = elements.removeAt(elements.size - 1)
        yieldAll(current.listAttributes().filter { it.value.startsWith("@+id/") })
        elements.addAll(current.listChildElements())
    }
}

internal data class EncodeManager(
    val module: ApkModule,
    val logger: Logger,
) {
    var manifest: AndroidManifestBlock = module.androidManifestBlock
    private val tableBlock: TableBlock? = module.tableBlock
    val packageBlock = tableBlock?.pickOne()
    val entryStore = TableEntryStore().apply {
        add(frameworkTable)
        tableBlock?.let { add(it) }
    }
    var manifestXml: XMLDocument = manifest.decodeToXml(entryStore, packageBlock?.id ?: 0)
    val typeTable by lazy {
        packageBlock?.listAllSpecTypePair()?.flatMap {
            it.listTypeBlocks().map { block ->
                var type = block.typeName
                if (!type.endsWith("s")) type = "${type}s"
                "values${block.qualifiers}/${type}" to block
            }
        }?.toMap()?.toMutableMap() ?: HashMap()
    }
    val encodeMaterials =
        tableBlock?.let { table ->
            EncodeMaterials().apply {
                currentPackage = packageBlock
                addPackageIds(ResourceIds().apply { loadPackageBlock(packageBlock) }.table.listPackages()[0])
                setAPKLogger(logger)
                table.frameWorks.forEach {
                    if (it is FrameworkTable) {
                        addFramework(it)
                    }
                }
            }
        }
    val valuesEncoder = ValuesEncoder(encodeMaterials)

    /**
     * A map of resource files from their actual name to their archive name.
     * Example: res/drawable-hdpi/icon.png -> res/4a.png
     */
    val resFileTable = module.listResFiles().associate { "res/${it.buildPath()}" to it.filePath }

    fun generateResourceMappings() = typeTable.flatMap { (_, typeBlock) ->
        typeBlock.listEntries(true).map {
            ResourceElement(
                it.typeName,
                it.name,
                it.resourceId.toLong()
            )
        }
    }

    fun finalize() {
        // Scan for @+id registrations.
        module.apkArchive.listInputSources().forEach {
            if (it is XMLEncodeSource) {
                it.xmlSource.xmlDocument.scanIdRegistrations().forEach { attr ->
                    val name = attr.value.split('/').last()
                    logger.info("Found id registration: $name")
                    typeTable["values/ids"]!!.getOrCreateEntry(name).also { entry ->
                        (entry.tableEntry as ResTableEntry).value.valueAsBoolean = false
                        logger.warn("name: ${entry.name}")
                    }
                    attr.value = "@id/$name"
                }
            }
        }

        /*
        val resXml = XMLFileEncoder(encodeMaterials ?: manifestEncodeMaterials).encode(manifestXml)
        manifest = AndroidManifestBlock()
        manifest.readBytes(ByteArrayInputStream(resXml.bytes))
        module.setManifest(manifest)
         */
        // Update package block name if necessary.
        tableBlock?.packageArray?.listItems()?.forEach {
            it.name = manifest.packageName
        }
    }
}