package com.example.hbs.psi.impl

import com.example.hbs.psi.HbCloseBlockMustache
import com.example.hbs.psi.HbOpenBlockMustache
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.example.hbs.icons.HandlebarsIcons // поправленный импорт
import javax.swing.Icon

class HbOpenBlockMustacheImpl(astNode: ASTNode) :
    HbBlockMustacheImpl(astNode), HbOpenBlockMustache {

    // Поскольку getPairedElement в интерфейсе возвращает HbCloseBlockMustache, возвращаем nullable
    override fun getPairedElement(): HbCloseBlockMustache? {
        val closeBlockElement: PsiElement? = this.parent?.lastChild
        return closeBlockElement as? HbCloseBlockMustache
    }

    // getIcon теперь корректно переопределяет метод
    override fun getIcon(flags: Int): Icon? {
        return HandlebarsIcons.Elements.OpenBlock
    }
}
