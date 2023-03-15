package app.revanced.patcher.apk

import com.reandroid.apk.ResourceIds
import com.reandroid.apk.xmlencoder.EncodeMaterials
import com.reandroid.common.Frameworks

class EncodeMaterials(private val apk: Apk) : EncodeMaterials() {
    init {
        setAPKLogger(apk.logger)
        addPackageIds(ResourceIds().apply {
            loadPackageBlock(apk.packageBlock!!)
        }.table.listPackages()[0])
        addFramework(Frameworks.getAndroid())
        currentPackage = apk.packageBlock
    }

    private val layoutTypeBlock = apk.typeTable["values/layouts"]

    override fun resolveLocalResourceId(type: String, name: String) = if (type == "+id") {
        apk.logger.info("Creating new entry for: $name")
        layoutTypeBlock!!.getOrCreateEntry(name).resourceId
    } else super.resolveLocalResourceId(type, name)
}