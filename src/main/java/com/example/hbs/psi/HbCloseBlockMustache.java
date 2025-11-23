package com.example.hbs.psi;

/**
 * Element for close block mustaches: "{{/foo}}"
 */
public interface HbCloseBlockMustache extends HbBlockMustache {

  @Override
  HbOpenBlockMustache getPairedElement();
}
