package app.revanced.patcher.apk.arsc

import app.revanced.patcher.apk.Apk
import app.revanced.patcher.logging.Logger
import com.reandroid.apk.AndroidFrameworks
import com.reandroid.apk.ApkModule
import com.reandroid.apk.ResourceIds
import com.reandroid.apk.xmlencoder.EncodeMaterials
import com.reandroid.apk.xmlencoder.EncodeUtil
import com.reandroid.apk.xmlencoder.ValuesEncoder
import com.reandroid.apk.xmlencoder.XMLEncodeSource
import com.reandroid.apk.xmlencoder.XMLFileEncoder
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.TypeBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.util.FrameworkTable
import com.reandroid.arsc.value.ResTableEntry
import com.reandroid.common.TableEntryStore
import com.reandroid.xml.XMLDocument
import java.io.ByteArrayInputStream
import java.io.File

data class ResourceElement(val type: String, val name: String, val id: Long)

val frameworkTable: FrameworkTable = AndroidFrameworks.getLatest().tableBlock


internal fun XMLDocument.scanIdRegistrations() = sequence {
    val elements = mutableListOf(documentElement)
    while (elements.size != 0) {
        val current = elements.removeAt(elements.size - 1)
        yieldAll(current.listAttributes().filter { it.value.startsWith("@+id/") })
        elements.addAll(current.listChildElements())
    }
}

internal fun getTypeIndex(s: String): Pair<String, String>? {
    if (!s.startsWith("res")) {
        return null
    }
    val file = File(s)
    return if (s.startsWith("res/values")) EncodeUtil.getQualifiersFromValuesXml(file) to EncodeUtil.getTypeNameFromValuesXml(
        file
    ) else EncodeUtil.getQualifiersFromResFile(file) to EncodeUtil.getTypeNameFromResFile(file)
}

internal data class EncodeManager(
    val module: ApkModule,
    val logger: Logger,
) {
    private val tableBlock: TableBlock? = module.tableBlock
    val packageBlock = tableBlock?.pickOne()
    val entryStore = TableEntryStore().apply {
        add(frameworkTable)
        tableBlock?.let { add(it) }
    }

    val encodeMaterials =
        EncodeMaterials().apply {
            setAPKLogger(logger)
            if (tableBlock != null) {
                currentPackage = packageBlock
                addPackageIds(ResourceIds().apply { loadPackageBlock(packageBlock) }.table.listPackages()[0])
                tableBlock.frameWorks.forEach {
                    if (it is FrameworkTable) {
                        addFramework(it)
                    }
                }
            } else {
                // Initialize with an empty Package/TableBlock, so we can still encode the manifest (we have to create a TableBlock to avoid NPEs).
                currentPackage = TableBlock().apply { packageArray.add(PackageBlock()) }.pickOne()
                addFramework(frameworkTable)
            }
        }

    val valuesEncoder = ValuesEncoder(encodeMaterials)

    /**
     * A map of resource files from their actual name to their archive name.
     * Example: res/drawable-hdpi/icon.png -> res/4a.png
     */
    val resFileTable = module.listResFiles().associate { "res/${it.buildPath()}" to it.filePath }

    fun generateResourceMappings() =
        packageBlock?.listAllSpecTypePair()?.flatMap { it.listTypeBlocks() }?.flatMap { it.listEntries(true) }
            ?.map { ResourceElement(it.typeName, it.name, it.resourceId.toLong()) }

    fun findTypeBlock(qualifiers: String, type: String): TypeBlock? =
        packageBlock?.listAllSpecTypePair()?.find { it.typeName == type }?.getTypeBlock(qualifiers)

    fun finalize() {
        // Scan for @+id registrations.
        module.apkArchive.listInputSources().forEach {
            if (it is XMLEncodeSource) {
                it.xmlSource.xmlDocument.scanIdRegistrations().forEach { attr ->
                    val name = attr.value.split('/').last()
                    logger.trace("Registering ID: $name")
                    packageBlock!!.getOrCreate("", "id", name).also { entry ->
                        (entry.tableEntry as ResTableEntry).value.valueAsBoolean = false
                    }
                    attr.value = "@id/$name"
                }
            }
        }

        val updatedManifest =
            module.apkArchive.getInputSource(Apk.MANIFEST_NAME).openStream().use { AndroidManifestBlock.load(it) }
        module.setManifest(updatedManifest)
        module.androidManifestBlock.refresh()

        // Update package block name if necessary.
        packageBlock?.let {
            it.name = updatedManifest.packageName
        }
    }
}