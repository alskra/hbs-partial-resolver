package com.example.hbs.psi.impl

import com.example.hbs.psi.HbMustacheName
import com.intellij.lang.ASTNode

class HbMustacheNameImpl(node: ASTNode) : HbPsiElementImpl(node), HbMustacheName {
    override fun getName(): String = text
}
