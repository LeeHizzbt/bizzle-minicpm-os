package com.bizzle.minicpm.data

import android.content.Context
import android.os.StatFs
import com.bizzle.minicpm.core.FileRole
import com.bizzle.minicpm.core.ModelCatalog
import com.bizzle.minicpm.core.ModelFile
import com.bizzle.minicpm.core.ModelSpec
import java.io.File

/** Where model files live (app-private storage, removed on uninstall). */
class ModelFiles(ctx: Context) {
    val dir: File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "models").apply { mkdirs() }

    fun file(f: ModelFile) = File(dir, f.localName)
    fun part(f: ModelFile) = File(dir, f.localName + ".part")
    fun isDone(f: ModelFile) = file(f).let { it.exists() && it.length() == f.sizeBytes }
    fun partialBytes(f: ModelFile) = part(f).let { if (it.exists()) it.length() else 0L }

    /** Ready to load = main file plus (for vision models) the standard projector. */
    fun isReady(m: ModelSpec) = m.files.filter { it.role != FileRole.MMPROJ_DETAIL }.all { isDone(it) }
    fun hasAny(m: ModelSpec) = m.files.any { isDone(it) || partialBytes(it) > 0 }
    fun missingBytes(m: ModelSpec) = m.files.filter { it.role != FileRole.MMPROJ_DETAIL && !isDone(it) }.sumOf { it.sizeBytes - partialBytes(it) }

    fun freeBytes(): Long = runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(0L)
    fun usedBytes(): Long = dir.listFiles().orEmpty().sumOf { it.length() }

    /** Deletes a model's files, keeping any file another downloaded model still uses (shared projectors). */
    fun delete(m: ModelSpec) {
        val keep = ModelCatalog.all.filter { it.id != m.id && isReady(it) }.flatMap { o -> o.files.map { it.localName } }.toSet()
        m.files.filter { it.localName !in keep }.forEach { file(it).delete(); part(it).delete() }
    }
}
