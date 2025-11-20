package com.example.hbs

import com.intellij.patterns.PlatformPatterns
import com.intellij.patterns.StandardPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.openapi.diagnostic.Logger

private val LOG = Logger.getInstance("com.example.hbs.PsiPartialReferenceContributor")

class PsiPartialReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        val pattern = PlatformPatterns.psiElement(PsiElement::class.java)
            .inFile(PlatformPatterns.psiFile().withName(StandardPatterns.string().endsWith(".hbs")))
        registrar.registerReferenceProvider(pattern, PsiPartialReferenceProvider())
        LOG.info("PsiPartialReferenceContributor registered provider for .hbs files")
    }
}
