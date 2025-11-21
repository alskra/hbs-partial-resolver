package com.example.hbs.psi.impl

import com.example.hbs.psi.HbCloseBlockMustache
import com.example.hbs.psi.HbOpenBlockMustache
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import icons.HandlebarsIcons
import javax.swing.Icon

class HbOpenBlockMustacheImpl(astNode: ASTNode) :
    HbBlockMustacheImpl(astNode), HbOpenBlockMustache {

    override fun getPairedElement(): HbCloseBlockMustache? {
        val closeBlockElement: PsiElement? = parent.lastChild
        return closeBlockElement as? HbCloseBlockMustache
    }

    override fun getIcon(flags: Int): Icon? {
        return HandlebarsIcons.Elements.OpenBlock
    }
}
