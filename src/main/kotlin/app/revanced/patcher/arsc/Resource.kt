package app.revanced.patcher.arsc

import app.revanced.patcher.apk.Apk
import com.reandroid.arsc.decoder.ValueDecoder
import com.reandroid.arsc.decoder.ValueDecoder.EncodeResult
import com.reandroid.arsc.value.Entry
import com.reandroid.arsc.value.ValueType
import com.reandroid.arsc.value.array.ArrayBag
import com.reandroid.arsc.value.array.ArrayBagItem
import com.reandroid.arsc.value.style.StyleBag
import com.reandroid.arsc.value.style.StyleBagItem

sealed interface Resource {
    val complex: Boolean

    fun write(entry: Entry, apk: Apk)
}

sealed class ScalarResource(internal val valueType: ValueType) : Resource {
    override val complex = false
    internal abstract fun data(resources: Apk.Resources): Int

    override fun write(entry: Entry, apk: Apk) {
        entry.setValueAsRaw(valueType, data(apk.resources))
    }

    internal open fun toArrayItem(resources: Apk.Resources) = ArrayBagItem.create(valueType, data(resources))
    internal open fun toStyleItem(resources: Apk.Resources) = StyleBagItem.create(valueType, data(resources))

    internal class Simple(valueType: ValueType, val value: Int) : ScalarResource(valueType) {
        override fun data(resources: Apk.Resources) = value
    }
}

internal fun encoded(encodeResult: EncodeResult?) = encodeResult?.let { ScalarResource.Simple(it.valueType, it.value) }
    ?: throw Apk.ApkException.Encode("Failed to encode value")

fun color(hex: String): ScalarResource = encoded(ValueDecoder.encodeColor(hex))
fun dimension(value: String): ScalarResource = encoded(ValueDecoder.encodeDimensionOrFraction(value))
fun float(n: Float): ScalarResource = ScalarResource.Simple(ValueType.FLOAT, n.toBits())
fun integer(n: Int): ScalarResource = ScalarResource.Simple(ValueType.INT_DEC, n)

class Array(private val elements: Collection<ScalarResource>) : Resource {
    override val complex = true

    override fun write(entry: Entry, apk: Apk) {
        ArrayBag.create(entry).addAll(elements.map { it.toArrayItem(apk.resources) })
    }
}

class Style(private val elements: Map<String, ScalarResource>, private val parent: String? = null) : Resource {
    override val complex = true

    override fun write(entry: Entry, apk: Apk) {
        val style = StyleBag.create(entry)
        val res = apk.resources
        parent?.let {
            style.parentId = res.resolve(parent)
        }

        style.putAll(elements.mapKeys { StyleBag.resolve(res.global.encodeMaterials, it.key) }
            .mapValues { it.value.toStyleItem(res) })
    }
}

class StringResource(val value: String) : ScalarResource(ValueType.STRING) {
    private fun tableString(resources: Apk.Resources) = resources.tableBlock?.stringPool?.getOrCreate(value) ?: throw Apk.ApkException.Encode("Cannot encode a string for an Apk that does not have a resource table.")
    override fun data(resources: Apk.Resources) = tableString(resources).index
    override fun toArrayItem(resources: Apk.Resources) = ArrayBagItem.string(tableString(resources))
    override fun toStyleItem(resources: Apk.Resources) = StyleBagItem.string(tableString(resources))
}

class ReferenceResource(val ref: String) : ScalarResource(ValueType.REFERENCE) {
    override fun data(resources: Apk.Resources) = resources.resolve(ref)
}