package app.revanced.patcher.apk

import com.reandroid.apk.ResourceIds
import com.reandroid.apk.xmlencoder.EncodeMaterials
import com.reandroid.arsc.chunk.PackageBlock
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

    private val idTypeBlock = apk.typeTable["values/ids"]

    override fun resolveLocalResourceId(type: String, name: String): Int {
        if (type == "+id") {
            apk.logger.info("Creating new entry for: $name")
            val entry = idTypeBlock!!.getOrCreateEntry(name)
            currentPackage.onEntryAdded(entry)
        }
        return super.resolveLocalResourceId(type.removePrefix("+"), name)
    }
}
