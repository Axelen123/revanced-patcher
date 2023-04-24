package app.revanced.patcher.arsc

import app.revanced.patcher.apk.Apk
import com.reandroid.arsc.decoder.ValueDecoder
import com.reandroid.arsc.decoder.ValueDecoder.EncodeResult
import com.reandroid.arsc.value.Entry
import com.reandroid.arsc.value.ValueType
import com.reandroid.arsc.value.array.ArrayBag
import com.reandroid.arsc.value.array.ArrayBagItem
import com.reandroid.arsc.value.plurals.PluralsBag
import com.reandroid.arsc.value.plurals.PluralsBagItem
import com.reandroid.arsc.value.plurals.PluralsQuantity
import com.reandroid.arsc.value.style.StyleBag
import com.reandroid.arsc.value.style.StyleBagItem

sealed class Resource(val complex: Boolean) {
    internal abstract fun write(entry: Entry, resources: Apk.Resources)
}

sealed class ScalarResource(private val valueType: ValueType) : Resource(false) {
    protected abstract fun data(resources: Apk.Resources): Int

    override fun write(entry: Entry, resources: Apk.Resources) {
        entry.setValueAsRaw(valueType, data(resources))
    }

    internal open fun toArrayItem(resources: Apk.Resources) = ArrayBagItem.create(valueType, data(resources))
    internal open fun toStyleItem(resources: Apk.Resources) = StyleBagItem.create(valueType, data(resources))

    internal class Simple(valueType: ValueType, val value: Int) : ScalarResource(valueType) {
        override fun data(resources: Apk.Resources) = value
    }
}

private fun encoded(encodeResult: EncodeResult?) = encodeResult?.let { ScalarResource.Simple(it.valueType, it.value) }
    ?: throw Apk.ApkException.Encode("Failed to encode value")

fun color(hex: String): ScalarResource = encoded(ValueDecoder.encodeColor(hex))
fun dimension(value: String): ScalarResource = encoded(ValueDecoder.encodeDimensionOrFraction(value))
fun float(n: Float): ScalarResource = ScalarResource.Simple(ValueType.FLOAT, n.toBits())
fun integer(n: Int): ScalarResource = ScalarResource.Simple(ValueType.INT_DEC, n)
fun reference(resourceId: Int): ScalarResource = ScalarResource.Simple(ValueType.REFERENCE, resourceId)

class Array(private val elements: Collection<ScalarResource>) : Resource(true) {
    override fun write(entry: Entry, resources: Apk.Resources) {
        ArrayBag.create(entry).addAll(elements.map { it.toArrayItem(resources) })
    }
}

class Style(private val elements: Map<String, ScalarResource>, private val parent: String? = null) : Resource(true) {
    override fun write(entry: Entry, resources: Apk.Resources) {
        val style = StyleBag.create(entry)
        parent?.let {
            style.parentId = resources.resolve(parent)
        }

        style.putAll(
            elements.asIterable().associate {
                StyleBag.resolve(resources.global.encodeMaterials, it.key) to it.value.toStyleItem(resources)
            })
    }
}

class Plurals(private val elements: Map<String, String>) : Resource(true) {
    override fun write(entry: Entry, resources: Apk.Resources) {
        val plurals = PluralsBag.create(entry)

        plurals.putAll(elements.asIterable().associate { (k, v) ->
            PluralsQuantity.valueOf(k) to PluralsBagItem.string(resources.tableBlock!!.stringPool.getOrCreate(v))
        })
    }
}

class StringResource(val value: String) : ScalarResource(ValueType.STRING) {
    private fun tableString(resources: Apk.Resources) = resources.tableBlock?.stringPool?.getOrCreate(value)
        ?: throw Apk.ApkException.Encode("Cannot encode a string for an Apk that does not have a resource table.")

    override fun data(resources: Apk.Resources) = tableString(resources).index
    override fun toArrayItem(resources: Apk.Resources) = ArrayBagItem.string(tableString(resources))
    override fun toStyleItem(resources: Apk.Resources) = StyleBagItem.string(tableString(resources))
}

class ReferenceResource(val ref: String) : ScalarResource(ValueType.REFERENCE) {
    override fun data(resources: Apk.Resources) = resources.resolve(ref)
}