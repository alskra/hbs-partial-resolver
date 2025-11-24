package com.example.hbs;

import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.refactoring.rename.RenameHandler;
import com.intellij.refactoring.rename.RenameProcessor;
import com.intellij.util.IncorrectOperationException;
import com.intellij.openapi.application.ReadAction;

public class HbsPartialRenameHandler implements RenameHandler {

    @Override
    public boolean isAvailableOnDataContext(com.intellij.openapi.actionSystem.DataContext dataContext) {
        PsiElement[] elements = PsiElement.EMPTY_ARRAY;
        // Здесь просто проверяем, что в контексте есть HBS файл
        Object psiFileObj = dataContext.getData(PsiFile.class.getName());
        if (psiFileObj instanceof PsiFile) {
            PsiFile file = (PsiFile) psiFileObj;
            return file.getName().endsWith(".hbs");
        }
        return false;
    }

    @Override
    public boolean isRenaming(com.intellij.openapi.actionSystem.DataContext dataContext) {
        return isAvailableOnDataContext(dataContext);
    }

    @Override
    public void invoke(Project project, PsiElement[] elements, com.intellij.openapi.actionSystem.DataContext dataContext) {
        for (PsiElement element : elements) {
            if (element instanceof PsiFile) {
                PsiFile psiFile = (PsiFile) element;
                if (psiFile.getName().endsWith(".hbs")) {
                    renameFileWithReferences(project, psiFile);
                }
            }
        }
    }

    @Override
    public void invoke(Project project, com.intellij.openapi.editor.Editor editor, PsiFile file,
                       com.intellij.openapi.actionSystem.DataContext dataContext) {
        if (file != null && file.getName().endsWith(".hbs")) {
            renameFileWithReferences(project, file);
        }
    }

    private void renameFileWithReferences(Project project, PsiFile file) {
        String oldName = file.getVirtualFile().getNameWithoutExtension();
        String newName = oldName; // IDE откроет диалог переименования

        // Запуск стандартного RenameProcessor
        RenameProcessor processor = new RenameProcessor(project, file, newName, false, false);
        processor.run();

        // Обновляем все SegmentReference
        ReadAction.run(() -> {
            PsiElement[] allElements = PsiTreeUtil.collectElements(file.getContainingFile(), element -> true);
            for (PsiElement el : allElements) {
                for (PsiReference ref : el.getReferences()) {
                    if (ref instanceof SegmentReference) {
                        try {
                            ((SegmentReference) ref).bindToElement(file);
                        } catch (IncorrectOperationException ignored) {}
                    }
                }
            }
        });
    }
}
