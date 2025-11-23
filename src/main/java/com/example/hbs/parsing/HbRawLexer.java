package com.example.hbs.parsing;

import com.intellij.lexer.FlexAdapter;


public class HbRawLexer extends FlexAdapter {
  public HbRawLexer() {
    super(new _HbLexer(null));
  }
}
