package com.example.hbs

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReferenceBase
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.application.ReadAction

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
}
