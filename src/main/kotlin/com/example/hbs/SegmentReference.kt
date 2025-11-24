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
     *
     * Подход:
     * - собираем возможные "базы" (parentParts-resolved dir, source roots, project base, source dir)
     * - для каждой базы считаем относительный путь к targetVf, нормализуем (удаляем .hbs у последнего сегмента)
     * - пробуем резолвить нормализованный путь через PathResolver; если резолв даёт тот же target (или его папку),
     *   считаем путь валидным и выбираем его
     * - если ни один кандидат не подошёл — используем наиболее подходящий относительный путь (fallback)
     *
     * Исправление: для вставки теперь заменяем **весь исходный путь** (все сегменты `a/b/c`), а не только последний сегмент,
     * чтобы избежать дублирования (пример: `a/b/c/c` -> `a/b/c`).
     */
    @Throws(IncorrectOperationException::class)
    override fun bindToElement(newElement: PsiElement): PsiElement {
        val targetPsiFile = newElement.containingFile ?: throw IncorrectOperationException("Target has no containing file")
        val targetVf = targetPsiFile.virtualFile ?: throw IncorrectOperationException("Target VirtualFile missing")

        val sourcePsiFile = element.containingFile ?: throw IncorrectOperationException("Source has no containing file")
        val sourceVf = sourcePsiFile.virtualFile ?: throw IncorrectOperationException("Source VirtualFile missing")

        val project = sourcePsiFile.project
        val resolver = PathResolver(project)

        val parentParts = if (idx == 0) emptyList<String>() else segments.subList(0, idx)

        // Соберём список баз для проверки в порядке приоритета
        val bases = mutableListOf<VirtualFile?>()

        // 1) базовая директория, соответствующая parentParts (если есть)
        if (parentParts.isNotEmpty()) {
            val parentVf = ReadAction.compute<VirtualFile?, RuntimeException> {
                resolver.resolveSegmentToVirtualFile(parentParts, parentParts.size - 1, false)
            }
            bases.add(parentVf)
        }

        // 2) Source roots
        val roots = ReadAction.compute<List<VirtualFile>, RuntimeException> { resolver.getRoots() }
        bases.addAll(roots)

        // 3) project base
        bases.add(project.baseDir)

        // 4) директория исходного файла
        bases.add(sourceVf.parent)

        // Уникализируем и уберём nullы
        val uniqueBases = bases.filterNotNull().distinct()

        // Функция нормализации — убирает .hbs у последнего сегмента
        fun normalize(path: String): String {
            if (!path.endsWith(".hbs")) return path
            val parts = path.split('/')
            val last = parts.last().removeSuffix(".hbs")
            return if (parts.size == 1) last else parts.dropLast(1).plus(last).joinToString("/")
        }

        var chosen: String? = null
        var fallback: String? = null

        // Перебираем базы и пробуем подобрать корректный кандидат
        for (base in uniqueBases) {
            val rel = VfsUtilCore.getRelativePath(targetVf, base, '/') ?: continue
            val candidate = normalize(rel)

            // запомним первый попавшийся как fallback
            if (fallback == null) fallback = candidate

            // попробуем резолвить candidate (разбиваем на сегменты) и проверить, резолвится ли он в нужный файл/папку
            val parts = if (candidate.isEmpty()) emptyList() else candidate.split('/')

            val resolved: VirtualFile? = ReadAction.compute<VirtualFile?, RuntimeException> {
                if (parts.isEmpty()) return@compute null
                resolver.resolveSegmentToVirtualFile(parts, parts.size - 1, false)
            }

            if (resolved != null) {
                if (resolved == targetVf) {
                    chosen = candidate
                    break
                }
                if (!targetVf.isDirectory && resolved == targetVf.parent) {
                    chosen = candidate
                    break
                }
                if (targetVf.isDirectory && resolved == targetVf) {
                    chosen = candidate
                    break
                }
            } else {
                if (parts.size >= 2) {
                    val parentPartsPath = parts.subList(0, parts.size - 1)
                    val parentResolved: VirtualFile? = ReadAction.compute<VirtualFile?, RuntimeException> {
                        resolver.resolveSegmentToVirtualFile(parentPartsPath, parentPartsPath.size - 1, false)
                    }
                    if (parentResolved != null) {
                        val lastSeg = parts.last()
                        val possibleFile = ReadAction.compute<VirtualFile?, RuntimeException> {
                            parentResolved.findChild(lastSeg) ?: parentResolved.findChild("$lastSeg.hbs")
                        }
                        if (possibleFile != null && (possibleFile == targetVf || possibleFile == targetVf.parent?.findChild(targetVf.name))) {
                            chosen = candidate
                            break
                        }
                    }
                }
            }
        }

        var replacementCandidate = chosen ?: fallback ?: run {
            if (targetVf.isDirectory) targetVf.name else targetVf.name.removeSuffix(".hbs")
        }

        // Если исходный путь содержал дублирование последнего сегмента (например ".../header/header"), отдадим предпочтение варианту без дублирования
        if (parentParts.isNotEmpty() && parentParts.last() == segments[idx]) {
            replacementCandidate = parentParts.joinToString("/")
        }

        val replacement = replacementCandidate

        // Попытаемся найти полный исходный путь внутри текста PSI-элемента (segments.joinToString("/")).
        // Если найдём — заменим весь этот диапазон. Это предотвращает дублирование.
        val fullPathText: String = ReadAction.compute<String, RuntimeException> { element.text }
        val fullPath = segments.joinToString("/")

        val (replaceStartOffset, replaceEndOffset) = run {
            val foundIndex = fullPathText.indexOf(fullPath)
            if (foundIndex >= 0) {
                val start = element.textRange.startOffset + foundIndex
                val end = start + fullPath.length
                Pair(start, end)
            } else {
                // fallback: прежнее поведение — заменять только rangeInElement (последний сегмент)
                val rangeInElement = rangeInElement
                val start = element.textRange.startOffset + rangeInElement.startOffset
                val end = element.textRange.startOffset + rangeInElement.endOffset
                Pair(start, end)
            }
        }

        WriteCommandAction.runWriteCommandAction(project) {
            val doc: Document = PsiDocumentManager.getInstance(project).getDocument(sourcePsiFile)
                ?: throw IncorrectOperationException("Document is null")

            val start = replaceStartOffset
            val end = replaceEndOffset

            if (start < 0 || end > doc.textLength || start > end) {
                throw IncorrectOperationException("Invalid range to replace: $start..$end")
            }

            doc.replaceString(start, end, replacement)
            PsiDocumentManager.getInstance(project).commitDocument(doc)
        }

        val updatedOffset = element.textRange.startOffset + rangeInElement.startOffset
        val updated = sourcePsiFile.findElementAt(updatedOffset)
        return updated ?: newElement
    }
}
