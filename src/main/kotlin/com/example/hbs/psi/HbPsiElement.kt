package com.example.hbs.psi

import com.intellij.openapi.util.NlsSafe
import com.intellij.psi.PsiElement

/**
 * Base for all Handlebars/Mustache elements
 */
interface HbPsiElement : PsiElement {

    @get:NlsSafe
    val name: String
}
