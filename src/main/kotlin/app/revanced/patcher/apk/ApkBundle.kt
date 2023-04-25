package app.revanced.patcher.apk

import app.revanced.patcher.Patcher
import app.revanced.patcher.PatcherOptions
import com.reandroid.apk.ResourceIds
import com.reandroid.apk.xmlencoder.EncodeMaterials
import com.reandroid.arsc.util.FrameworkTable
import com.reandroid.common.TableEntryStore
import java.io.File

/**
 * An [Apk] file of type [Apk.Split].
 *
 * @param base The apk file of type [Apk.Base].
 * @param split The [Apk.Split] files.
 */
class ApkBundle(
    val base: Apk.Base,
    val split: Split? = null
) {

    companion object {
        fun new(files: List<File>): ApkBundle {
            var base: Apk.Base? = null
            var splits = mutableListOf<Apk.Split>()
            files.forEach {
                val apk = Apk.new(it)
                when (apk) {
                    is Apk.Base -> {
                        if (base != null) {
                            throw IllegalArgumentException("Cannot have more than one base apk")
                        }
                        base = apk
                    }
                    is Apk.Split -> {
                        splits.add(apk)
                    }
                }
            }
            val split = if (splits.size > 0) Split(splits) else null
            return ApkBundle(base ?: throw IllegalArgumentException("Base Apk not found"), split)
        }
    }

    val all = sequence {
        yield(base)
        split?.all?.let {
            yieldAll(it)
        }
    }

    /**
     * Refresh some additional resources in the [ApkBundle] that have been patched.
     *
     * @param options The [PatcherOptions] of the [Patcher].
     * @return A sequence of the [Apk] files which are being refreshed.
     */
    internal fun finalize(options: PatcherOptions) = all.map {
        var exception: Apk.ApkException? = null
        try {
            it.finalize(options)
        } catch (e:Apk.ApkException) {
            exception = e
        }

        SplitApkResult(it, exception)
    }

    inner class GlobalResources {
        internal val entryStore = TableEntryStore()
        internal val resTable: ResourceIds.Table.Package
        internal val encodeMaterials = EncodeMaterials()

        fun query(config: String) = split?.configs?.get(config)?.resources ?: base.resources

        /**
         * Resolve a resource id for the specified resource.
         *
         * @param type The type of the resource.
         * @param name The name of the resource.
         * @return The id of the resource.
         */
        fun resolve(type: String, name: String) = resTable.getResourceId(type, name) ?: throw Apk.ApkException.ReferenceError(type, name)

        init {
            val resourceIds = ResourceIds()
            all.map { it.resources }.forEach {
                if (it.tableBlock != null) {
                    entryStore.add(it.tableBlock)
                    resourceIds.loadPackageBlock(it.packageBlock)
                }
                it.global = this
            }

            base.resources.also {
                encodeMaterials.currentPackage = it.packageBlock
                resTable =
                    resourceIds.table.listPackages().onEach { pkg -> encodeMaterials.addPackageIds(pkg) }
                        .single { pkg -> pkg.id == it.packageBlock!!.id.toByte() }

                it.tableBlock!!.frameWorks.forEach { fw ->
                    if (fw is FrameworkTable) {
                        entryStore.add(fw)
                        encodeMaterials.addFramework(fw)
                    }
                }
            }
        }
    }

    val resources = GlobalResources()

    /**
     * Class for [Apk.Split].
     *
     * @param library The apk file of type [Apk.Base].
     * @param asset The apk file of type [Apk.Base].
     * @param language The apk file of type [Apk.Base].
     */
    class Split(
        val all: List<Apk.Split>
    ) {
        val configs = all.associateBy { it.config }
    }

    /**
     * The result of writing a [Split] [Apk] file.
     *
     * @param apk The corresponding [Apk] file.
     * @param exception The optional [Apk.ApkException] when an exception occurred.
     */
    data class SplitApkResult(val apk: Apk, val exception: Apk.ApkException? = null)
}