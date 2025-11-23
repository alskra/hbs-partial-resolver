package com.example.hbs.psi.impl;

import com.example.hbs.psi.HbNumberLiteral;
import com.intellij.lang.ASTNode;
import org.jetbrains.annotations.NotNull;

public class HbNumberLiteralImpl extends HbPsiElementImpl implements HbNumberLiteral {
  public HbNumberLiteralImpl(@NotNull ASTNode astNode) {
    super(astNode);
  }
}
