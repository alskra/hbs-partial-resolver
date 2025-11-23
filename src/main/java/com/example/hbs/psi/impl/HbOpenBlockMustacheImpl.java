// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.example.hbs.psi.impl;

import com.example.hbs.psi.HbCloseBlockMustache;
import com.example.hbs.psi.HbOpenBlockMustache;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import icons.HandlebarsIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

public class HbOpenBlockMustacheImpl extends HbBlockMustacheImpl implements HbOpenBlockMustache {
  public HbOpenBlockMustacheImpl(@NotNull ASTNode astNode) {
    super(astNode);
  }

  @Override
  public HbCloseBlockMustache getPairedElement() {
    PsiElement closeBlockElement = getParent().getLastChild();
    if (closeBlockElement instanceof HbCloseBlockMustache) {
      return (HbCloseBlockMustache)closeBlockElement;
    }

    return null;
  }

  @Override
  public @Nullable Icon getIcon(int flags) {
    return HandlebarsIcons.Elements.OpenBlock;
  }
}
