package me.rafaelldi.aspire.workload

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import me.rafaelldi.aspire.settings.AspireSettings

class UpdateWorkloadActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val shouldCheck = AspireSettings.getInstance().checkForNewVersions
        if (!shouldCheck) return
        AspireWorkloadService.getInstance(project).checkForUpdate()
    }
}