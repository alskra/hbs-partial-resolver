package com.example.hbs

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project

fun Document.editor(project: Project): Editor? {
    return EditorFactory.getInstance().getEditors(this, project).firstOrNull()
}
