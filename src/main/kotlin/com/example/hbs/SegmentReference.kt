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
        return if (targetFile.isDirectory) {
            psiManager.findDirectory(targetFile)
        } else {
            psiManager.findFile(targetFile)
        }
    }

    override fun getCanonicalText(): String = segments[idx]

    override fun getVariants(): Array<Any> {
        try {
            val resolver = PathResolver(element.project)
            val parentParts = if (idx == 0) emptyList() else segments.subList(0, idx)

            val parent: List<VirtualFile> = if (parentParts.isEmpty()) {
                // корневые варианты
                ReadAction.compute<List<VirtualFile>, RuntimeException> {
                    resolver.getRoots().flatMap { root: VirtualFile ->
                        root.children.filter { child: VirtualFile ->
                            child.isDirectory || child.name.endsWith(".hbs")
                        }
                    }
                }
            } else {
                val vf: VirtualFile? = ReadAction.compute<VirtualFile?, RuntimeException> {
                    resolver.resolveSegmentToVirtualFile(parentParts, parentParts.size - 1, false)
                }
                ReadAction.compute<List<VirtualFile>, RuntimeException> {
                    vf?.children?.filter { child: VirtualFile ->
                        child.isDirectory || child.name.endsWith(".hbs")
                    } ?: emptyList()
                }
            }

            return parent.map { f ->
                val name = if (f.isDirectory) f.name else f.nameWithoutExtension
                LookupElementBuilder.create(name)
            }.toTypedArray()
        } catch (t: Throwable) {
            return emptyArray()
        }
    }

    /**
     * Обновляет текст ссылки при привязке к новому PSI-элементу (вызывается IntelliJ при Move/Refactor).
     * Формирует относительный путь от каталога исходного файла к новому файлу/папке и заменяет соответствующий диапазон в документе.
     */
    @Throws(IncorrectOperationException::class)
    override fun bindToElement(newElement: PsiElement): PsiElement {
        // Найдём содержащие файлы/виртуальные файлы
        val targetPsiFile = newElement.containingFile ?: throw IncorrectOperationException("Target has no containing file")
        val targetVf = targetPsiFile.virtualFile ?: throw IncorrectOperationException("Target VirtualFile missing")

        val sourcePsiFile = element.containingFile ?: throw IncorrectOperationException("Source has no containing file")
        val sourceVf = sourcePsiFile.virtualFile ?: throw IncorrectOperationException("Source VirtualFile missing")

        // Относительный путь от директории исходного файла до targetVf
        val sourceDir = sourceVf.parent
            ?: throw IncorrectOperationException("Source file has no parent directory")

        var relative = VfsUtilCore.getRelativePath(targetVf, sourceDir, '/')

        if (relative == null) {
            // Попробуем от корня проекта
            val projectBase = sourcePsiFile.project.baseDir
            relative = VfsUtilCore.getRelativePath(targetVf, projectBase, '/')
        }

        // Если зашли в null — используем просто имя файла/папки
        if (relative == null) {
            relative = if (targetVf.isDirectory) targetVf.name else targetVf.name
        }

        // Если цель — файл .hbs, в шаблонах у нас варианты без расширения => убираем .hbs
        val replacement = if (!targetVf.isDirectory && relative.endsWith(".hbs")) {
            // убрать расширение только у последнего сегмента
            val parts = relative.split('/')
            val last = parts.last().removeSuffix(".hbs")
            if (parts.size == 1) last else parts.dropLast(1).plus(last).joinToString("/")
        } else {
            relative
        }

        val project = sourcePsiFile.project

        WriteCommandAction.runWriteCommandAction(project) {
            val doc: Document = PsiDocumentManager.getInstance(project).getDocument(sourcePsiFile)
                ?: throw IncorrectOperationException("Document is null")

            val rangeInElement = rangeInElement
            val start = element.textRange.startOffset + rangeInElement.startOffset
            val end = element.textRange.startOffset + rangeInElement.endOffset

            // Безопасность: проверим границы
            if (start < 0 || end > doc.textLength || start > end) {
                throw IncorrectOperationException("Invalid range to replace: $start..$end")
            }

            doc.replaceString(start, end, replacement)
            PsiDocumentManager.getInstance(project).commitDocument(doc)
        }

        // Попытка вернуть обновлённый PSI-элемент в позиции начала вставки
        val updatedOffset = element.textRange.startOffset + rangeInElement.startOffset
        val updated = sourcePsiFile.findElementAt(updatedOffset)
        return updated ?: newElement
    }
}
