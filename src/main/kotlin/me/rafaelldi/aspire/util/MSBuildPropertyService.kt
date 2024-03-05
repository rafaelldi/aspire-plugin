@file:Suppress("UnstableApiUsage")

package me.rafaelldi.aspire.util

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.jetbrains.rider.model.RdTargetFrameworkId
import com.jetbrains.rider.model.RdVersionInfo
import com.jetbrains.rider.runtime.RiderDotNetActiveRuntimeHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.nameWithoutExtension

@Service(Service.Level.PROJECT)
class MSBuildPropertyService(private val project: Project) {
    companion object {
        fun getInstance(project: Project) = project.service<MSBuildPropertyService>()

        private val LOG = logger<MSBuildPropertyService>()

        private val json by lazy {
            Json { ignoreUnknownKeys = true }
        }
    }

    suspend fun getExecutableFromMSBuildProperties(projectPath: Path): Pair<Path, RdTargetFrameworkId?>? {
        val runtime = RiderDotNetActiveRuntimeHost.getInstance(project).dotNetCoreRuntime.value
        if (runtime == null) {
            LOG.warn("Unable to find dotnet runtime")
            return null
        }

        val commandLine = GeneralCommandLine()
            .withExePath(runtime.cliExePath)
            .withParameters(
                listOf(
                    "build",
                    projectPath.absolutePathString(),
                    "-getTargetResult:Build"
                )
            )

        return withContext(Dispatchers.IO) {
            withBackgroundProgress(project, "Building ${projectPath.nameWithoutExtension}") {
                val output = ExecUtil.execAndGetOutput(commandLine)
                if (!output.checkSuccess(LOG)) {
                    return@withBackgroundProgress null
                }

                val buildTargetResult = json.decodeFromString<BuildTargetResultOutput>(output.stdout)
                if (!buildTargetResult.targetResults.build.result.equals("Success", true)) {
                    LOG.warn("Unable to get build target result")
                    return@withBackgroundProgress null
                }
                val buildItem = buildTargetResult.targetResults.build.items.firstOrNull()
                if (buildItem == null) {
                    LOG.warn("Unable to get build target result")
                    return@withBackgroundProgress null
                }

                val fullPath = Path(buildItem.fullPath)
                val executablePath =
                    if (SystemInfo.isWindows) fullPath.resolveSibling(fullPath.nameWithoutExtension + ".exe")
                    else fullPath.resolveSibling(fullPath.fileName)
                val targetFrameworkId = getTargetFrameworkId(buildItem.targetFrameworkVersion)

                return@withBackgroundProgress executablePath to targetFrameworkId
            }
        }
    }

    private fun getTargetFrameworkId(targetFrameworkVersion: String): RdTargetFrameworkId {
        val versionParts = targetFrameworkVersion.split('.').map { it.toInt() }
        val versionInfo = when (versionParts.size) {
            1 -> {
                RdVersionInfo(versionParts[0], 0, 0)
            }

            2 -> {
                RdVersionInfo(versionParts[0], versionParts[1], 0)
            }

            else -> {
                RdVersionInfo(versionParts[0], versionParts[1], versionParts[2])
            }
        }

        return RdTargetFrameworkId(versionInfo, ".NETCoreApp", targetFrameworkVersion, true, false)
    }
}