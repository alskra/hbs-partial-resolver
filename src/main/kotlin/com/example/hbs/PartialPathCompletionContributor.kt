package com.example.hbs

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiFile
import com.intellij.util.ProcessingContext
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager

class PartialPathCompletionContributor : CompletionContributor() {

    init {
        val provider = object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(
                parameters: CompletionParameters,
                context: ProcessingContext,
                result: CompletionResultSet
            ) {
                val psiFile: PsiFile = parameters.originalFile
                if (!psiFile.name.endsWith(".hbs", ignoreCase = true)) return

                val fileText = psiFile.text
                val caret = parameters.offset.coerceIn(0, fileText.length)

                val start = fileText.lastIndexOf("{{>", 0.coerceAtLeast(caret - 1))
                if (start == -1) return

                var idx = start + 3
                while (idx < fileText.length && fileText[idx].isWhitespace()) idx++

                if (idx < fileText.length && (fileText[idx] == '\'' || fileText[idx] == '"')) idx++
                while (idx < caret && fileText[idx].isWhitespace()) idx++

                val rawPath = if (idx <= caret) fileText.substring(idx, caret) else ""

                val endsWithSlash = rawPath.endsWith("/")
                val segments = rawPath.split("/").filter { it.isNotEmpty() }

                val traverse: List<String>
                val prefix: String

                if (endsWithSlash) {
                    traverse = segments
                    prefix = ""
                } else {
                    if (segments.isEmpty()) {
                        traverse = emptyList()
                        prefix = ""
                    } else {
                        traverse = segments.dropLast(1)
                        prefix = segments.lastOrNull() ?: ""
                    }
                }

                val project: Project = psiFile.project
                val resolver = PathResolver(project)

                val parentVirtualFile: VirtualFile? = if (traverse.isEmpty()) {
                    null
                } else {
                    ReadAction.compute<VirtualFile?, RuntimeException> {
                        resolver.resolveSegmentToVirtualFile(traverse, traverse.size - 1, false)
                    }
                }

                val roots = (ProjectRootManager.getInstance(project).contentSourceRoots + project.baseDir).distinct()

                val candidates: List<VirtualFile> = ReadAction.compute<List<VirtualFile>, RuntimeException> {
                    if (parentVirtualFile != null) {
                        parentVirtualFile.children.filter { it.isDirectory || it.name.endsWith(".hbs") }
                    } else {
                        roots.flatMap { root: VirtualFile ->
                            root.children.filter { it.isDirectory || it.name.endsWith(".hbs") }
                        }
                    }
                }

                val seen = mutableSetOf<String>()

                for (child in candidates) {
                    val name = if (child.isDirectory) child.name else child.nameWithoutExtension
                    if (name.contains(prefix, ignoreCase = true) && seen.add("${child.path}::$name")) {
                        val icon = if (child.isDirectory) AllIcons.Nodes.Folder else child.fileType.icon
                        result.addElement(LookupElementBuilder.create(name).withIcon(icon))
                    }
                }
            }
        }

        // --- подключаем provider (без четвертого аргумента) ---
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            provider
        )
    }
}
