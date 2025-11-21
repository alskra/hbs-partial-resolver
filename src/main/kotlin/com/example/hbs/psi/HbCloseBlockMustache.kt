package com.example.hbs.psi

/**
 * Element for close block mustaches: "{{/foo}}"
 */
interface HbCloseBlockMustache : HbBlockMustache {

    override fun getPairedElement(): HbOpenBlockMustache
}
