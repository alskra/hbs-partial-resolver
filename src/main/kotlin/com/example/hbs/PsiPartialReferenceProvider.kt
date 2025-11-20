package com.example.hbs

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceProvider
import com.intellij.util.ProcessingContext
import com.intellij.openapi.application.ReadAction

private val LOG = Logger.getInstance("com.example.hbs.PsiPartialReferenceProvider")

class PsiPartialReferenceProvider : PsiReferenceProvider() {

    private val regex = Regex("""\{\{>\s*(['"]?)([^\s'"}]+)\1""")

    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        try {
            LOG.info("Psi element: '${element.text}', class: ${element.javaClass.name}")

            val file = element.containingFile ?: return emptyArray()
            val vfile = file.virtualFile ?: return emptyArray()
            if (!vfile.extension.equals("hbs", ignoreCase = true)) return emptyArray()

            val elementText = element.text
            val match = regex.find(elementText) ?: return emptyArray()
            val path = match.groupValues[2]
            if (path.isBlank()) return emptyArray()

            val pathIndexInElement = match.value.indexOf(path)
            if (pathIndexInElement < 0) return emptyArray()

            val segments = path.split('/').filter { it.isNotBlank() }
            if (segments.isEmpty()) return emptyArray()

            val segmentOffsets = mutableListOf<Pair<Int, Int>>()
            var cursor = pathIndexInElement
            for (seg in segments) {
                val segStart = cursor
                val segEnd = cursor + seg.length
                segmentOffsets.add(Pair(segStart, segEnd))
                cursor = segEnd + 1
            }

            val references = mutableListOf<PsiReference>()
            val resolver = PathResolver(element.project)

            for (idx in segments.indices) {
                val segStartInElement = segmentOffsets[idx].first
                val segEndInElement = segmentOffsets[idx].second
                val range = TextRange(segStartInElement, segEndInElement)

                val isLast = idx == segments.lastIndex

                val targetFile: VirtualFile? = ReadAction.compute<VirtualFile?, RuntimeException> {
                    resolver.resolveSegmentToVirtualFile(segments, idx, isLast)
                }

                if (targetFile != null) {
                    references.add(SegmentReference(element, range, segments, idx, targetFile))
                }
            }

            return references.toTypedArray()
        } catch (t: Throwable) {
            LOG.warn("Exception in PsiPartialReferenceProvider", t)
            return emptyArray()
        }
    }
}
