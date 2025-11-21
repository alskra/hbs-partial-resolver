package com.example.hbs.psi.impl

import com.example.hbs.psi.HbMustache
import com.intellij.lang.ASTNode

open class HbMustacheImpl(node: ASTNode) : HbPsiElementImpl(node), HbMustache
