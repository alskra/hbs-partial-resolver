package com.example.hbs.psi.impl;

import com.example.hbs.psi.HbComment;
import com.intellij.lang.ASTNode;
import org.jetbrains.annotations.NotNull;

public class HbCommentImpl extends HbPsiElementImpl implements HbComment {
  public HbCommentImpl(@NotNull ASTNode astNode) {
    super(astNode);
  }
}
