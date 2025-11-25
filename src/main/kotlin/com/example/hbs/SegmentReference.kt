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
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiNameIdentifierOwner

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

    /**
     * bindToElement — связывает ссылку с элементом. Не занимается физическим переименованием.
     * При рефакторинге IDEA сама переименует файл/папку; здесь мы только формируем корректный текст ссылки.
     */
    @Throws(IncorrectOperationException::class)
    override fun bindToElement(newElement: PsiElement): PsiElement {
        val sourcePsiFile = element.containingFile
            ?: throw IncorrectOperationException("Source has no containing file")
        val project = sourcePsiFile.project
        val resolver = PathResolver(project)
        val fullSourcePath = segments.joinToString("/")

        // Получаем все source roots
        val sourceRoots = ReadAction.compute<List<VirtualFile>, RuntimeException> { resolver.getRoots() }

        // Попытка получить VirtualFile из newElement (если newElement — файл/элемент в файле)
        val effectiveTargetVf: VirtualFile? = ReadAction.compute<VirtualFile?, RuntimeException> {
            when {
                newElement is PsiFileSystemItem -> newElement.virtualFile
                newElement.containingFile != null -> newElement.containingFile.virtualFile
                else -> null
            }
        }

        // Если VF доступен — формируем относительный путь; иначе пробуем взять имя из newElement
        val replacementFromVf: String? = if (effectiveTargetVf != null) {
            val rootContainingTarget = sourceRoots.firstOrNull { VfsUtilCore.isAncestor(it, effectiveTargetVf, true) }
            if (rootContainingTarget != null) {
                VfsUtilCore.getRelativePath(effectiveTargetVf, rootContainingTarget, '/')?.removeSuffix(".hbs")
            } else {
                VfsUtilCore.getRelativePath(effectiveTargetVf, project.baseDir, '/')?.removeSuffix(".hbs")
            } ?: effectiveTargetVf.nameWithoutExtension
        } else null

        val replacementFromName: String? = ReadAction.compute<String?, RuntimeException> {
            when {
                newElement is PsiNamedElement -> newElement.name
                newElement is PsiNameIdentifierOwner -> newElement.name
                else -> null
            }
        }

        val replacement = replacementFromVf ?: replacementFromName
            ?: throw IncorrectOperationException("Cannot determine replacement text from newElement")

        // Вычисление диапазона замены внутри документа (абсолютные оффсеты)
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

        // Коммитим все документы перед операцией
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        var resultingPsi: PsiElement? = null
        WriteCommandAction.runWriteCommandAction(project) {
            val doc: Document = PsiDocumentManager.getInstance(project).getDocument(sourcePsiFile)
                ?: throw IncorrectOperationException("Document is null")

            if (replaceStartOffset < 0 || replaceEndOffset > doc.textLength || replaceStartOffset > replaceEndOffset) {
                throw IncorrectOperationException("Invalid range to replace: $replaceStartOffset..$replaceEndOffset")
            }

            doc.replaceString(replaceStartOffset, replaceEndOffset, replacement)
            PsiDocumentManager.getInstance(project).commitDocument(doc)

            resultingPsi = sourcePsiFile.findElementAt(replaceStartOffset)
        }

        return resultingPsi ?: newElement
    }

    /**
     * Когда рефакторинг переименования целевого элемента выполняется (Shift+F6),
     * платформа вызывает handleElementRename на ссылках. Здесь мы должны
     * изменить только соответствующий сегмент (segments[idx]) в тексте ссылки.
     *
     * Примечание: не делаем никаких VFS/PSI-rename'ов — этим занимается платформа.
     */
    override fun handleElementRename(newElementName: String): PsiElement {
        val sourcePsiFile = element.containingFile
            ?: throw IncorrectOperationException("Source has no containing file")
        val project = sourcePsiFile.project

        // Снимем .hbs если пользователь ввёл его в newElementName — ссылки должны быть без расширения
        val cleanedNewName = if (newElementName.endsWith(".hbs")) {
            newElementName.removeSuffix(".hbs")
        } else newElementName

        // Текст элемента (внутри PSI) — работаем в ReadAction
        val elementText: String = ReadAction.compute<String, RuntimeException> { element.text }
        val fullSourcePath = segments.joinToString("/")

        // Ищем fullSourcePath внутри elementText и заменяем только нужный сегмент.
        // Если не найден — используем rangeInElement как fallback и ищем сегмент внутри него.
        val (segAbsStart, segAbsEnd) = run {
            val foundIndex = elementText.indexOf(fullSourcePath)
            if (foundIndex >= 0) {
                // prefix (включая слэш) перед сегментом
                val prefix = if (idx == 0) "" else segments.subList(0, idx).joinToString("/") + "/"
                val segStartInElement = foundIndex + prefix.length
                val segEndInElement = segStartInElement + segments[idx].length
                val absStart = element.textRange.startOffset + segStartInElement
                val absEnd = element.textRange.startOffset + segEndInElement
                Pair(absStart, absEnd)
            } else {
                // fallback: ищем внутри rangeInElement
                val r = rangeInElement
                val inner = elementText.substring(r.startOffset, r.endOffset)
                val foundInner = inner.indexOf(segments[idx])
                if (foundInner >= 0) {
                    val segStartInElement = r.startOffset + foundInner
                    val segEndInElement = segStartInElement + segments[idx].length
                    val absStart = element.textRange.startOffset + segStartInElement
                    val absEnd = element.textRange.startOffset + segEndInElement
                    Pair(absStart, absEnd)
                } else {
                    // если не нашли — заменим весь rangeInElement целиком
                    val absStart = element.textRange.startOffset + r.startOffset
                    val absEnd = element.textRange.startOffset + r.endOffset
                    Pair(absStart, absEnd)
                }
            }
        }

        // Выполняем замену в документе
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        WriteCommandAction.runWriteCommandAction(project) {
            val doc = PsiDocumentManager.getInstance(project).getDocument(sourcePsiFile)
                ?: throw IncorrectOperationException("Document is null")

            if (segAbsStart < 0 || segAbsEnd > doc.textLength || segAbsStart > segAbsEnd) {
                throw IncorrectOperationException("Invalid segment range to replace: $segAbsStart..$segAbsEnd")
            }

            doc.replaceString(segAbsStart, segAbsEnd, cleanedNewName)
            PsiDocumentManager.getInstance(project).commitDocument(doc)
        }

        // Попробуем вернуть обновлённый элемент на позиции начала вставки
        return sourcePsiFile.findElementAt(segAbsStart) ?: element
    }
}
