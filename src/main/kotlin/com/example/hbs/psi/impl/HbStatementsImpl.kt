package com.example.hbs.psi.impl

import com.example.hbs.psi.HbStatements
import com.intellij.lang.ASTNode

class HbStatementsImpl(astNode: ASTNode) :
    HbPsiElementImpl(astNode), HbStatements
