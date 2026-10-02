package com.vidgod.editor

import android.app.Application
import com.vidgod.editor.data.ProjectRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class VidGodApp : Application() {
    /** Scope that outlives screens, used for saving. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var repository: ProjectRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.vidgod.editor.data.Diagnostics.install(this)
        repository = ProjectRepository(this)
    }

    companion object {
        lateinit var instance: VidGodApp
            private set
    }
}
