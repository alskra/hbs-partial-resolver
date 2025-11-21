package com.example.hbs.psi.impl

import com.example.hbs.psi.HbCloseBlockMustache
import com.example.hbs.psi.HbOpenBlockMustache
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement

class HbCloseBlockMustacheImpl(astNode: ASTNode) :
    HbBlockMustacheImpl(astNode), HbCloseBlockMustache {

    override fun getPairedElement(): HbOpenBlockMustache? {
        val openBlockElement: PsiElement? = parent.firstChild
        return openBlockElement as? HbOpenBlockMustache
    }
}
