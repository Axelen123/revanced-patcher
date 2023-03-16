package app.revanced.patcher.apk

import com.reandroid.apk.ResourceIds
import com.reandroid.apk.xmlencoder.EncodeMaterials
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.value.ResTableEntry
import com.reandroid.arsc.value.ValueType
import com.reandroid.common.Frameworks

class EncodeMaterials(private val apk: Apk) : EncodeMaterials() {
    init {
        setAPKLogger(apk.logger)
        apk.logger.info("block: ${apk.packageBlock}, $apk")
        apk.packageBlock?.let {
            addPackageIds(ResourceIds().apply {
                loadPackageBlock(it)
            }.table.listPackages()[0])
        }
        currentPackage =
            apk.packageBlock ?: PackageBlock() // Create an empty PackageBlock() so we can encode the manifest.

        addFramework(Frameworks.getAndroid())
    }

    private val idTypeBlock = apk.typeTable["values/ids"]?.also {
        it.listEntries(true).forEach {e ->
            apk.logger.info("mogus: ${e.id}")
        }
    }

    override fun resolveLocalResourceId(type: String, name: String): Int {
        if (type == "+id") {
            apk.logger.info("Creating new entry for: $name")
            val entry = idTypeBlock!!.getOrCreateEntry(name).also {
                // it.setValueAsRaw(ValueType.INT_BOOLEAN, 0)
                val value = (it.tableEntry as ResTableEntry).value
                value.valueType = ValueType.INT_BOOLEAN
                value.data = 0
            }
            apk.logger.info("husk: ${entry.tableEntry.value}")
            apk.logger.warn("${entry.resourceId} vs ${super.resolveLocalResourceId(type.removePrefix("+"), name)}")
            currentPackage.onEntryAdded(entry)
        }
        return super.resolveLocalResourceId(type.removePrefix("+"), name)
    }
}
