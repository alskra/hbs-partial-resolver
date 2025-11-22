package com.example.hbs.psi.impl

import com.example.hbs.psi.HbPartialName
import com.intellij.lang.ASTNode

class HbPartialNameImpl(node: ASTNode) : HbPsiElementImpl(node), HbPartialName {

    override val name: String
        get() = text
}
