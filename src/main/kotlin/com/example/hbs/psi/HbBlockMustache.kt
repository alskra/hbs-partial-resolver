package com.example.hbs.psi

/**
 * Base type for all mustaches which define blocks (openBlock, openInverseBlock, closeBlock... others in the future?)
 */
interface HbBlockMustache : HbMustache {

    /**
     * Returns the [HbMustacheName] element for this block. i.e. the element wrapping "foo.bar" in
     * {{#foo.bar baz}}
     * and
     * {{/foo.bar}}
     *
     * @return the [HbMustacheName] for this block or null if none found (which should only happen if there are
     *         currently parse errors in the file)
     */
    fun getBlockMustacheName(): HbMustacheName?

    /**
     * Get the block element paired with this one
     *
     * @return the matching [HbBlockMustache] element (i.e. for {{#foo}}, returns {{/foo}} and vice-versa)
     *         or null if none found (which should only happen if there are currently parse errors in the file)
     */
    fun getPairedElement(): HbBlockMustache?
}
