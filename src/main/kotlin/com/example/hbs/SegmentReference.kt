package com.example.hbs

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReferenceBase
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.editor.Document
import com.intellij.util.IncorrectOperationException

class SegmentReference(
    element: PsiElement,
    textRange: TextRange,
    private val segments: List<String>,
    private val idx: Int,
    private val targetFile: VirtualFile
) : PsiReferenceBase<PsiElement>(element, textRange) {

    override fun resolve(): PsiFileSystemItem? {
        val psiManager = PsiManager.getInstance(element.project)
        return if (targetFile.isDirectory) psiManager.findDirectory(targetFile)
        else psiManager.findFile(targetFile)
    }

    override fun getCanonicalText(): String = segments[idx]

    override fun getVariants(): Array<Any> {
        try {
            val resolver = PathResolver(element.project)
            val parentParts = if (idx == 0) emptyList() else segments.subList(0, idx)

            val parent: List<VirtualFile> = if (parentParts.isEmpty()) {
                ReadAction.compute<List<VirtualFile>, RuntimeException> {
                    resolver.getRoots().flatMap { root ->
                        root.children.filter { it.isDirectory || it.name.endsWith(".hbs") }
                    }
                }
            } else {
                val vf: VirtualFile? = ReadAction.compute<VirtualFile?, RuntimeException> {
                    resolver.resolveSegmentToVirtualFile(parentParts, parentParts.size - 1, false)
                }
                ReadAction.compute<List<VirtualFile>, RuntimeException> {
                    vf?.children?.filter { it.isDirectory || it.name.endsWith(".hbs") } ?: emptyList()
                }
            }

            return parent.map {
                val name = if (it.isDirectory) it.name else it.nameWithoutExtension
                LookupElementBuilder.create(name)
            }.toTypedArray()
        } catch (t: Throwable) {
            return emptyArray()
        }
    }

    @Throws(IncorrectOperationException::class)
    override fun bindToElement(newElement: PsiElement): PsiElement {
        val targetPsiFile = newElement.containingFile
            ?: throw IncorrectOperationException("Target has no containing file")
        val targetVf = targetPsiFile.virtualFile
            ?: throw IncorrectOperationException("Target VirtualFile missing")

        val sourcePsiFile = element.containingFile
            ?: throw IncorrectOperationException("Source has no containing file")
        val sourceVf = sourcePsiFile.virtualFile
            ?: throw IncorrectOperationException("Source VirtualFile missing")

        val project = sourcePsiFile.project
        val resolver = PathResolver(project)

        val fullSourcePath = segments.joinToString("/")

        // Получаем все sources root из настроек IDE
        val sourceRoots = ReadAction.compute<List<VirtualFile>, RuntimeException> { resolver.getRoots() }

        // Ищем sources root, в котором находится target
        val rootContainingTarget = sourceRoots.firstOrNull { VfsUtilCore.isAncestor(it, targetVf, true) }

        // Строим путь с приоритетом sources root, иначе fallback на parent исходного файла
        val targetRelative: String = if (rootContainingTarget != null) {
            VfsUtilCore.getRelativePath(targetVf, rootContainingTarget, '/')?.removeSuffix(".hbs")
        } else {
            VfsUtilCore.getRelativePath(targetVf, sourceVf.parent, '/')?.removeSuffix(".hbs")
        } ?: targetVf.nameWithoutExtension

        val replacement = targetRelative

        // Заменяем весь исходный путь
        val fullPathText: String = ReadAction.compute<String, RuntimeException> { element.text }
        val (replaceStartOffset, replaceEndOffset) = run {
            val foundIndex = fullPathText.indexOf(fullSourcePath)
            if (foundIndex >= 0) {
                val start = element.textRange.startOffset + foundIndex
                val end = start + fullSourcePath.length
                Pair(start, end)
            } else {
                val rangeInElement = rangeInElement
                val start = element.textRange.startOffset + rangeInElement.startOffset
                val end = element.textRange.startOffset + rangeInElement.endOffset
                Pair(start, end)
            }
        }

        WriteCommandAction.runWriteCommandAction(project) {
            val doc: Document = PsiDocumentManager.getInstance(project).getDocument(sourcePsiFile)
                ?: throw IncorrectOperationException("Document is null")
            if (replaceStartOffset < 0 || replaceEndOffset > doc.textLength || replaceStartOffset > replaceEndOffset) {
                throw IncorrectOperationException("Invalid range to replace: $replaceStartOffset..$replaceEndOffset")
            }
            doc.replaceString(replaceStartOffset, replaceEndOffset, replacement)
            PsiDocumentManager.getInstance(project).commitDocument(doc)
        }

        val updatedOffset = element.textRange.startOffset + rangeInElement.startOffset
        val updated = sourcePsiFile.findElementAt(updatedOffset)
        return updated ?: newElement
    }
}
