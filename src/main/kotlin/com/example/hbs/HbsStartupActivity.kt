package com.example.hbs

import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.project.Project
import com.intellij.openapi.editor.EditorFactory

class HbsStartupActivity : StartupActivity {
    override fun runActivity(project: Project) {
        val listener = HbsSlashListener(project)
        // Подключаем ко всем документам
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(listener, project)
    }
}
