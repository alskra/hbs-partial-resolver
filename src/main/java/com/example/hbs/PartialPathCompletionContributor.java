package com.example.hbs;

import com.dmarcotte.handlebars.psi.*;
import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;

public class PartialPathCompletionContributor extends CompletionContributor {

    public PartialPathCompletionContributor() {

        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), new CompletionProvider<CompletionParameters>() {
            @Override
            protected void addCompletions(@NotNull CompletionParameters parameters,
                                          @NotNull ProcessingContext context,
                                          @NotNull CompletionResultSet result) {

                PsiFile psiFile = parameters.getOriginalFile();
                if (!psiFile.getName().endsWith(".hbs")) return;

                PsiElement element = psiFile.findElementAt(parameters.getOffset());
                if (element == null) return;

                // Ищем ближайший HbPartial (partial reference)
                HbPartial partial = findParentPartial(element);
                if (partial == null) return;

                String rawPath = partial.getName();
                if (rawPath == null) rawPath = "";

                boolean endsWithSlash = rawPath.endsWith("/");
                String[] segments = rawPath.split("/");

                // Определяем текущий сегмент для автокомплита
                String prefix;
                HbPartial parentPartial = null;
                if (endsWithSlash || segments.length == 0) {
                    prefix = "";
                } else {
                    prefix = segments[segments.length - 1];
                }

                Set<String> seen = new HashSet<>();

                // Получаем список всех partials в файле через PSI
                psiFile.accept(new HbRecursiveVisitor() {
                    @Override
                    public void visitPartial(@NotNull HbPartial p) {
                        super.visitPartial(p);
                        String name = p.getName();
                        if (name != null && name.contains(prefix) && seen.add(name)) {
                            LookupElementBuilder el = LookupElementBuilder.create(name)
                                    .withIcon(AllIcons.FileTypes.Any_type);
                            result.addElement(el);
                        }
                    }
                });
            }
        });
    }

    private HbPartial findParentPartial(PsiElement element) {
        while (element != null && !(element instanceof HbPartial)) {
            element = element.getParent();
        }
        return (HbPartial) element;
    }
}
