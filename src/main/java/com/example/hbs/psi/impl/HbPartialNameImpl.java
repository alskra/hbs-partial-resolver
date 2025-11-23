package com.example.hbs.psi.impl;

import com.example.hbs.psi.HbPartialName;
import com.intellij.lang.ASTNode;
import org.jetbrains.annotations.NotNull;

public class HbPartialNameImpl extends HbPsiElementImpl implements HbPartialName {
  public HbPartialNameImpl(@NotNull ASTNode astNode) {
    super(astNode);
  }

  @Override
  public String getName() {
    return getText();
  }
}
