package com.example.hbs

import com.intellij.codeInsight.AutoPopupController
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.openapi.project.Project

class HbsSlashListener(private val project: Project) : DocumentListener {
    override fun documentChanged(event: DocumentEvent) {
        val text = event.document.charsSequence
        val offset = event.offset

        if (event.newFragment.toString() != "/") return

        val virtualFile = FileDocumentManager.getInstance().getFile(event.document) ?: return
        if (!virtualFile.name.endsWith(".hbs", ignoreCase = true)) return

        val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return

        val start = text.lastIndexOf("{{>", 0.coerceAtLeast(offset - 1))
        if (start == -1 || offset <= start + 3) return

        // ✅ Тут подключаем extension
        val editor = event.document.editor(project) ?: return

        AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
    }
}
