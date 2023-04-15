package app.revanced.patcher.apk

import app.revanced.patcher.Patcher
import app.revanced.patcher.PatcherOptions
import com.reandroid.apk.ApkBundle
import com.reandroid.apk.ResourceIds
import com.reandroid.apk.xmlencoder.EncodeMaterials
import com.reandroid.arsc.util.FrameworkTable
import com.reandroid.common.TableEntryStore

/**
 * An [Apk] file of type [Apk.Split].
 *
 * @param base The apk file of type [Apk.Base].
 * @param split The [Apk.Split] files.
 */
class ApkBundle(
    val base: Apk.Base,
    split: Split? = null,
) {
    /**
     * The [Apk.Split] files.
     */
    var split = split
        internal set

    private val all = sequence {
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
    internal fun finalize(options: PatcherOptions) = sequence {
        with(base) {
            finalize(options)

            yield(SplitApkResult.Write(this))
        }

        split?.all?.forEach { splitApk ->
            with(splitApk) {
                finalize(options)

                yield(SplitApkResult.Write(this))
            }
        }
    }

    inner class GlobalResources {
        val entryStore = TableEntryStore()
        val resTable: ResourceIds.Table.Package
        val encodeMaterials = EncodeMaterials()

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
        library: Apk.Split.Library,
        asset: Apk.Split.Asset,
        language: Apk.Split.Language
    ) {
        var library = library
            internal set
        var asset = asset
            internal set
        var language = language
            internal set

        val all get() = listOfNotNull(library, asset, language)
    }

    /**
     * The result of writing a [Split] [Apk] file.
     *
     * @param apk The corresponding [Apk] file.
     * @param exception The optional [Apk.ApkException] when an exception occurred.
     */
    sealed class SplitApkResult(val apk: Apk, val exception: Apk.ApkException? = null) {
        /**
         * The result of writing a [Split] [Apk] file.
         *
         * @param apk The corresponding [Apk] file.
         * @param exception The optional [Apk.ApkException] when an exception occurred.
         */
        class Write(apk: Apk, exception: Apk.ApkException.Encode? = null) : SplitApkResult(apk, exception)
    }
}