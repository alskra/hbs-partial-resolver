package com.example.hbs.psi.impl

import com.example.hbs.psi.HbCloseBlockMustache
import com.example.hbs.psi.HbOpenBlockMustache
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import icons.HandlebarsIcons
import javax.swing.Icon

class HbOpenBlockMustacheImpl(astNode: ASTNode) :
    HbBlockMustacheImpl(astNode), HbOpenBlockMustache {

    override fun getPairedElement(): HbCloseBlockMustache {
        // Идём по всем детям родителя в обратном порядке и ищем HbCloseBlockMustache
        var element: PsiElement? = parent.lastChild
        while (element != null) {
            if (element is HbCloseBlockMustache) return element
            element = element.prevSibling
        }
        // Если не нашли — выбрасываем исключение, так как метод не nullable
        throw IllegalStateException("Paired close block not found for $this")
    }

    override fun getIcon(flags: Int): Icon? {
        return HandlebarsIcons.Elements.OpenBlock
    }
}
