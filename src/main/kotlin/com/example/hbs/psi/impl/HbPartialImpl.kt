package com.example.hbs.psi.impl

import com.example.hbs.psi.HbPartial
import com.example.hbs.psi.HbPartialName
import com.intellij.lang.ASTNode
import icons.HandlebarsIcons
import javax.swing.Icon

class HbPartialImpl(node: ASTNode) : HbPlainMustacheImpl(node), HbPartial {

    override val name: String?
        get() = children
            .filterIsInstance<HbPartialName>()
            .firstOrNull()
            ?.name

    override fun getIcon(flags: Int): Icon? = HandlebarsIcons.Elements.OpenPartial
}
