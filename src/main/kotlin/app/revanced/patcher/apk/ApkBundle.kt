package app.revanced.patcher.apk

import com.reandroid.apk.ApkModule
import com.reandroid.apk.xmlencoder.EncodeMaterials
import com.reandroid.arsc.util.FrameworkTable
import com.reandroid.arsc.value.Entry
import com.reandroid.arsc.value.ResConfig
import com.reandroid.common.TableEntryStore
import com.reandroid.identifiers.TableIdentifier
import java.io.File

/**
 * An [Apk] file of type [Apk.Split].
 *
 * @param files A list of apk files to load.
 */
class ApkBundle(files: List<File>) {
    /**
     * The [Apk.Base] of this [ApkBundle].
     */
    val base: Apk.Base

    /**
     * A map containing all the [Apk.Split]s in this bundle associated by their configuration.
     */
    val splits: Map<String, Apk.Split>?

    private fun ApkModule.isFeatureModule() = androidManifestBlock.manifestElement.let {
        it.searchAttributeByName("isFeatureSplit")?.valueAsBoolean == true || it.searchAttributeByName("configForSplit") != null
    }

    init {
        var baseApk: Apk.Base? = null
        val splitList = mutableListOf<Apk.Split>()

        files.forEach {
            val module = ApkModule.loadApkFile(it)
            when {
                module.isBaseModule -> {
                    if (baseApk != null) {
                        throw IllegalArgumentException("Cannot have more than one base apk")
                    }
                    baseApk = Apk.Base(module)
                }

                !module.isFeatureModule() -> {
                    val config = module.split.removePrefix("config.")

                    splitList.add(
                        when {
                            config.length == 2 -> Apk.Split.Language(config, module)
                            Apk.Split.Library.architectures.contains(config) -> Apk.Split.Library(config, module)
                            ResConfig.Density.valueOf(config) != null -> Apk.Split.Asset(config, module)
                            else -> throw IllegalArgumentException("Unknown split: $config")
                        }
                    )
                }
            }
        }

        splits = splitList.takeIf { it.size > 0 }?.let { splitList.associateBy { it.config } }
        base = baseApk ?: throw IllegalArgumentException("Base apk not found")
    }

    /**
     * A [Sequence] yielding all [Apk]s in this [ApkBundle].
     */
    val all = sequence {
        yield(base)
        splits?.values?.let {
            yieldAll(it)
        }
    }

    /**
     * Write all the [Apk]s inside the bundle to a folder.
     *
     * @param folder The folder to write the [Apk]s to.
     * @return A sequence of the [Apk] files which are being refreshed.
     */
    internal fun write(folder: File) = all.map {
        val file = folder.resolve(it.toString())
        var exception: Apk.ApkException? = null
        try {
            it.write(file)
        } catch (e: Apk.ApkException) {
            exception = e
        }

        SplitApkResult(it, file, exception)
    }

    inner class ResourceTable {
        private val packageName = base.packageMetadata.packageName
        internal val entryStore = TableEntryStore()
        internal val encodeMaterials: EncodeMaterials
        internal val tableIdentifier: TableIdentifier
        private val modifiedResources = HashMap<String, HashMap<String, Int>>()


        // TODO: move this elsewhere
        /**
         * Get the [Apk.ResourceContainer] for the specified configuration.
         *
         * @param config The config to search for.
         */
        fun query(config: String) = splits?.get(config)?.resources ?: base.resources

        /**
         * Resolve a resource id for the specified resource.
         *
         * @param type The type of the resource.
         * @param name The name of the resource.
         * @return The id of the resource.
         */
        fun resolve(type: String, name: String) =
            modifiedResources[type]?.get(name)
                ?: tableIdentifier.get(packageName, type, name)?.resourceId
                ?: throw Apk.ApkException.InvalidReference(
                    type,
                    name
                )

        internal fun registerChanged(entry: Entry) {
            modifiedResources.getOrPut(entry.typeName, ::HashMap)[entry.name] = entry.id
        }

        init {
            encodeMaterials = object : EncodeMaterials() {
                override fun resolveLocalResourceId(type: String, name: String) = resolve(type, name)
            }
            tableIdentifier = encodeMaterials.tableIdentifier

            all.map { it.resources }.forEach {
                it.tableBlock?.let { table ->
                    entryStore.add(table)
                    tableIdentifier.load(table)
                }

                it.resourceTable = this
            }

            base.resources.also {
                encodeMaterials.currentPackage = it.packageBlock

                it.tableBlock!!.frameWorks.forEach { fw ->
                    if (fw is FrameworkTable) {
                        entryStore.add(fw)
                        encodeMaterials.addFramework(fw)
                    }
                }
            }
        }
    }

    /**
     * The global resource container.
     */
    val resources = ResourceTable()

    /**
     * The result of writing an [Apk] file.
     *
     * @param apk The corresponding [Apk] file.
     * @param file The location that the [Apk] was written to.
     * @param exception The optional [Apk.ApkException] when an exception occurred.
     */
    data class SplitApkResult(val apk: Apk, val file: File, val exception: Apk.ApkException? = null)
}