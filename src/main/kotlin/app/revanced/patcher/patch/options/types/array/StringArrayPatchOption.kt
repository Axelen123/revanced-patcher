package app.revanced.patcher.patch.options.types.array

import app.revanced.patcher.patch.Patch
import app.revanced.patcher.patch.options.PatchOption

/**
 * A [PatchOption] representing a [String] array.
 *
 * @param key The identifier.
 * @param default The default value.
 * @param title The title.
 * @param description A description.
 * @param required Whether the option is required.
 * @param validate The function to validate values of the option.
 *
 * @see PatchOption
 */
class StringArrayPatchOption private constructor(
    key: String,
    default: Array<String>?,
    title: String?,
    description: String?,
    required: Boolean,
    validate: (Array<String>?) -> Boolean
) : PatchOption<Array<String>>(key, default, title, description, required, validate) {
    companion object {
        /**
         * Create a new [StringArrayPatchOption] and add it to the current [Patch].
         *
         * @param key The identifier.
         * @param default The default value.
         * @param title The title.
         * @param description A description.
         * @param required Whether the option is required.
         * @param validate The function to validate values of the option.
         * 
         * @return The created [StringArrayPatchOption].
         *
         * @see StringArrayPatchOption
         * @see PatchOption
         */
        fun <T : Patch<*>> T.stringArrayPatchOption(
            key: String,
            default: Array<String>? = null,
            title: String? = null,
            description: String? = null,
            required: Boolean = false,
            validate: (Array<String>?) -> Boolean = { true }
        ) = StringArrayPatchOption(key, default, title, description, required, validate).also { options.register(it) }
    }
}
