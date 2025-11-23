package com.example.hbs.psi.impl;

import com.example.hbs.psi.HbParam;
import com.intellij.lang.ASTNode;
import org.jetbrains.annotations.NotNull;

public class HbParamImpl extends HbPsiElementImpl implements HbParam {
  public HbParamImpl(@NotNull ASTNode astNode) {
    super(astNode);
  }
}
