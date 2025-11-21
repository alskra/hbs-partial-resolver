package com.example.hbs.psi.impl

import com.example.hbs.psi.HbPlainMustache
import com.intellij.lang.ASTNode
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
open class HbPlainMustacheImpl(node: ASTNode) : HbMustacheImpl(node), HbPlainMustache
