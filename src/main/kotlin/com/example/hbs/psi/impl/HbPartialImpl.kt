package com.example.hbs.psi.impl

import com.example.hbs.psi.HbPartial
import com.example.hbs.psi.HbPartialName
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.extapi.psi.ASTWrapperPsiElement
import icons.HandlebarsIcons
import javax.swing.Icon

class HbPartialImpl(node: ASTNode) : HbPlainMustacheImpl(node), HbPartial {

    override val name: String
        get() {
            for (child in children) {
                if (child is HbPartialName) {
                    return child.name
                }
            }
            // Если имя не найдено, возвращаем пустую строку или можно бросить исключение
            return ""
        }

    override fun getIcon(flags: Int): Icon? = HandlebarsIcons.Elements.OpenPartial
}
