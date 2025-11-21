package com.example.hbs.psi

/**
 * Base element for mustaches which open blocks (i.e. "{{#foo}}" and "{{^foo}}")
 */
interface HbOpenBlockMustache : HbBlockMustache {

    override fun getPairedElement(): HbCloseBlockMustache
}
