package app.revanced.patcher.apk

import app.revanced.patcher.PatcherOptions
import app.revanced.patcher.logging.Logger
import com.reandroid.apk.ApkBundle
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock

/**
 * An [Apk] file of type [Apk.Split].
 *
 * @param base The apk file of type [Apk.Base].
 * @param split The [Apk.Split] files.
 */
class ApkBundle(
    val base: Apk.Base,
    split: Split? = null,
    val logger: Logger
) {
    /**
     * The [Apk.Split] files.
     */
    var split = split
        internal set

    /**
     * Refresh some additional resources in the [ApkBundle] that have been patched.
     *
     * @return A sequence of the [Apk] files which are being refreshed.
     */
    internal fun finalize() = sequence {
        with(base) {
            finalize()

            yield(SplitApkResult.Write(this))
        }

        split?.all?.forEach { splitApk ->
            with(splitApk) {
                finalize()

                yield(SplitApkResult.Write(this))
            }
        }
    }

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