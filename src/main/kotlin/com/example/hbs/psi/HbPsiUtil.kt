package com.example.hbs.psi

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

object HbPsiUtil {

    /**
     * Used to determine if an element is part of an "open tag" (i.e. "{{#open}}" or "{{^openInverse}}")
     * If the given element is the descendant of an [HbOpenBlockMustache], this method returns that parent.
     * Otherwise, returns null.
     */
    @JvmStatic
    fun findParentOpenTagElement(element: PsiElement): HbOpenBlockMustache? {
        return PsiTreeUtil.findFirstParent(element, true) { it is HbOpenBlockMustache } as? HbOpenBlockMustache
    }

    /**
     * Used to determine if an element is part of a "close tag" (i.e. "{{/closer}}")
     * If the given element is the descendant of an [HbCloseBlockMustache], this method returns that parent.
     * Otherwise, returns null.
     */
    @JvmStatic
    fun findParentCloseTagElement(element: PsiElement): HbCloseBlockMustache? {
        return PsiTreeUtil.findFirstParent(element, true) { it is HbCloseBlockMustache } as? HbCloseBlockMustache
    }

    /**
     * Tests to see if the given element is not the "root" statements expression of the grammar
     */
    @JvmStatic
    fun isNonRootStatementsElement(element: PsiElement): Boolean {
        val statementsParent = PsiTreeUtil.findFirstParent(element, true) { it is HbStatements }
        return element is HbStatements && statementsParent != null
    }
}
