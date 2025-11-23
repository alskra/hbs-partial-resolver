package com.example.hbs.psi.impl;

import com.example.hbs.psi.HbMustache;
import com.intellij.lang.ASTNode;
import org.jetbrains.annotations.NotNull;

public class HbMustacheImpl extends HbPsiElementImpl implements HbMustache {
  public HbMustacheImpl(@NotNull ASTNode astNode) {
    super(astNode);
  }
}
