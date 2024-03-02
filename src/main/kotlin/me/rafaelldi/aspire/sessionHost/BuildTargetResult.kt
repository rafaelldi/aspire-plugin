package me.rafaelldi.aspire.sessionHost

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BuildTargetResultOutput(
    @SerialName("TargetResults") val targetResults: TargetResults
)

@Serializable
data class TargetResults(
    @SerialName("Build") val build: TargetResultsBuild
)

@Serializable
data class TargetResultsBuild(
    @SerialName("Result") val result: String,
    @SerialName("Items") val items: List<BuildItems>
)

@Serializable
data class BuildItems(
    @SerialName("FullPath") val fullPath: String,
    @SerialName("TargetFrameworkVersion") val targetFrameworkVersion: String
)
