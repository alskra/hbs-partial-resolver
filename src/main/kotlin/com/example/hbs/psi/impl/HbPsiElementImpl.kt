package com.example.hbs.psi.impl

import com.example.hbs.psi.HbPsiElement
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.ItemPresentationProviders
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.source.resolve.reference.ReferenceProvidersRegistry

open class HbPsiElementImpl(node: ASTNode) : ASTWrapperPsiElement(node), HbPsiElement {

    override val name: String
        get() = text

    override fun getPresentation(): ItemPresentation? =
        ItemPresentationProviders.getItemPresentation(this)

    override fun getReferences(): Array<PsiReference> =
        ReferenceProvidersRegistry.getReferencesFromProviders(this)
}
