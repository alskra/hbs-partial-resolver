package com.example.hbs

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.Disposable
import com.intellij.util.messages.MessageBusConnection
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent

private val LOG = Logger.getInstance(PathResolver::class.java)

class PathResolver(private val project: Project) : Disposable {

    @Volatile
    private var cachedRoots: List<VirtualFile>? = null
    private val connection: MessageBusConnection = project.messageBus.connect(this)

    init {
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: MutableList<out VFileEvent>) {
                // при любых изменениях инвалидация кеша
                cachedRoots = null
            }
        })
    }

    private fun collectRootsInternal(): List<VirtualFile> {
        val list = mutableListOf<VirtualFile>()
        try {
            val modules = ModuleManager.getInstance(project).modules
            for (m in modules) {
                try {
                    val manager = ModuleRootManager.getInstance(m)
                    for (entry in manager.contentEntries) {
                        list.addAll(entry.sourceFolders.mapNotNull { it.file })
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}

        try {
            project.basePath?.let { base ->
                LocalFileSystem.getInstance().findFileByPath(base)?.let { list.add(it) }
            }
        } catch (_: Throwable) {}

        return list.distinct()
    }

    fun getRoots(): List<VirtualFile> {
        var r = cachedRoots
        if (r == null) {
            r = ReadAction.compute<List<VirtualFile>, RuntimeException> { collectRootsInternal() }
            cachedRoots = r
        }
        return r
    }

    /**
     * Проверяет существование сегмента
     */
    fun segmentExistsForIndex(parts: List<String>, idx: Int): Boolean {
        val isLast = idx == parts.size - 1
        return resolveSegmentToVirtualFile(parts, idx, isLast) != null
    }

    /**
     * Возвращает VirtualFile сегмента:
     * - Промежуточный → директория
     * - Последний → файл .hbs
     */
    fun resolveSegmentToVirtualFile(parts: List<String>, idx: Int, isLast: Boolean): VirtualFile? {
        val name = parts[idx]

        if (!isLast) {
            // промежуточный сегмент → директория
            for (root in getRoots()) {
                var cur: VirtualFile? = root
                for (i in 0..idx) {
                    cur = cur?.findChild(parts[i])
                    if (cur == null) break
                }
                if (cur != null && cur.isDirectory) return cur
            }
            return null
        }

        // последний сегмент → ищем файл .hbs внутри каталога, соответствующего предыдущим сегментам
        for (root in getRoots()) {
            val found = findFileForLastSegment(root, parts)
            if (found != null) return found
        }

        // fallback: если введено foo.hbs прямо (полное имя файла)
        for (root in getRoots()) {
            var cur: VirtualFile? = root
            for (i in 0 until idx) {
                cur = cur?.findChild(parts[i])
                if (cur == null || !cur.isDirectory) break
            }
            val lastFile = cur?.findChild(name)
            if (lastFile != null && !lastFile.isDirectory) return lastFile
        }

        return null
    }

    private fun findFileForLastSegment(root: VirtualFile, parts: List<String>): VirtualFile? {
        var cur: VirtualFile? = root
        for (i in 0 until parts.size - 1) {
            cur = cur?.findChild(parts[i])
            if (cur == null || !cur.isDirectory) return null
        }
        val name = parts.last()
        val candidates = listOf("_${name}.hbs", "${name}.hbs", "index.hbs")
        for (cand in candidates) {
            val f = cur?.findChild(cand)
            if (f != null && !f.isDirectory) return f
        }
        return null
    }

    /**
     * Собирает все существующие сегменты для автокомплита (ограничивает сканирование больших каталогов)
     */
    fun collectAllSegments(): Set<String> {
        val segments = mutableSetOf<String>()
        for (root in getRoots()) {
            ReadAction.run<RuntimeException> { collectSegmentsRecursively(root, "", segments) }
        }
        return segments
    }

    private fun collectSegmentsRecursively(dir: VirtualFile, prefix: String, segments: MutableSet<String>, depth: Int = 0) {
        if (!dir.isDirectory) return
        // ограничение глубины и фильтр по именам чтобы избежать node_modules/.git
        if (depth > 10) return
        for (child in dir.children) {
            val name = child.nameWithoutExtension
            if (name == "node_modules" || name == ".git") continue
            val path = if (prefix.isEmpty()) name else "$prefix/$name"
            segments.add(path)
            if (child.isDirectory) {
                collectSegmentsRecursively(child, path, segments, depth + 1)
            }
        }
    }

    override fun dispose() {
        connection.dispose()
    }
}
