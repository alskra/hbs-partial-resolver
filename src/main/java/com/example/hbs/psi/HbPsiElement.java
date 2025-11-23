package com.example.hbs.psi;

import com.intellij.openapi.util.NlsSafe;
import com.intellij.psi.PsiElement;

/**
 * Base for all Handlebars/Mustache elements
 */
public interface HbPsiElement extends PsiElement {

  @NlsSafe
  String getName();
}
