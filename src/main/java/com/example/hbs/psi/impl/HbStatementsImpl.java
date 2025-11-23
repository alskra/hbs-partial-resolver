package com.example.hbs.psi.impl;

import com.example.hbs.psi.HbStatements;
import com.intellij.lang.ASTNode;
import org.jetbrains.annotations.NotNull;

public class HbStatementsImpl extends HbPsiElementImpl implements HbStatements {
  public HbStatementsImpl(@NotNull ASTNode astNode) {
    super(astNode);
  }
}
