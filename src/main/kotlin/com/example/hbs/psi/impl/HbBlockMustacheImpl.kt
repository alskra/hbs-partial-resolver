// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.example.hbs.psi.impl

import com.example.hbs.psi.HbBlockMustache
import com.example.hbs.psi.HbMustacheName
import com.intellij.lang.ASTNode
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.extapi.psi.ASTWrapperPsiElement

abstract class HbBlockMustacheImpl(node: ASTNode) : HbMustacheImpl(node), HbBlockMustache {

    override fun getBlockMustacheName(): HbMustacheName? =
        PsiTreeUtil.getChildOfType(this, HbMustacheName::class.java)

    // `HbMustacheImpl` defines `val name: String` (non-null).
    // Возвращаем имя из блока, если оно есть, иначе используем super.name в качестве фоллбэка.
    override val name: String
        get() = getBlockMustacheName()?.name ?: super.name
}
