package com.vidgod.editor.data

import android.content.Context
import android.graphics.Bitmap
import com.vidgod.editor.model.Project
import com.vidgod.editor.model.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/** Lightweight info for the project list. */
data class ProjectSummary(
    val id: String,
    val name: String,
    val updatedAt: Long,
    val durationUs: Long,
    val coverFile: File?,
    val clipCount: Int,
)

/** Stores each project as JSON under `files/projects/<id>/project.json`. */
class ProjectRepository(private val context: Context) {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        coerceInputValues = true
    }

    private val root = File(context.filesDir, "projects").apply { mkdirs() }
    private val _projects = MutableStateFlow<List<ProjectSummary>>(emptyList())
    val projects: StateFlow<List<ProjectSummary>> = _projects.asStateFlow()

    private fun dir(id: String) = File(root, id)
    private fun file(id: String) = File(dir(id), "project.json")
    fun coverFile(id: String) = File(dir(id), "cover.jpg")

    /** Folder for media generated for a project (recordings, reversed clips, ...). */
    fun mediaDir(id: String) = File(dir(id), "media").apply { mkdirs() }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val list = root.listFiles()?.mapNotNull { d ->
            val f = File(d, "project.json")
            if (!f.exists()) return@mapNotNull null
            runCatching {
                val p = json.decodeFromString(Project.serializer(), f.readText())
                ProjectSummary(
                    id = p.id,
                    name = p.name,
                    updatedAt = p.updatedAt,
                    durationUs = p.durationUs,
                    coverFile = coverFile(p.id).takeIf { it.exists() },
                    clipCount = p.clips.size,
                )
            }.getOrNull()
        }.orEmpty().sortedByDescending { it.updatedAt }
        _projects.value = list
    }

    suspend fun load(id: String): Project? = withContext(Dispatchers.IO) {
        val f = file(id)
        if (!f.exists()) return@withContext null
        runCatching { json.decodeFromString(Project.serializer(), f.readText()) }.getOrNull()
    }

    suspend fun save(project: Project) = withContext(Dispatchers.IO) {
        val d = dir(project.id).apply { mkdirs() }
        val tmp = File(d, "project.json.tmp")
        tmp.writeText(json.encodeToString(Project.serializer(), project))
        val target = file(project.id)
        if (!tmp.renameTo(target)) {
            target.writeText(tmp.readText())
            tmp.delete()
        }
    }

    suspend fun saveCover(id: String, bitmap: Bitmap) = withContext(Dispatchers.IO) {
        runCatching {
            dir(id).mkdirs()
            coverFile(id).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        }
    }

    suspend fun create(name: String): Project {
        val p = Project(id = newId(), name = name)
        save(p)
        refresh()
        return p
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        dir(id).deleteRecursively()
        refresh()
    }

    suspend fun rename(id: String, name: String) {
        val p = load(id) ?: return
        save(p.copy(name = name, updatedAt = System.currentTimeMillis()))
        refresh()
    }

    suspend fun duplicate(id: String) = withContext(Dispatchers.IO) {
        val p = load(id) ?: return@withContext
        val copy = p.copy(id = newId(), name = p.name + " copy", updatedAt = System.currentTimeMillis())
        dir(id).copyRecursively(dir(copy.id), overwrite = true)
        save(copy)
        refresh()
    }
}
