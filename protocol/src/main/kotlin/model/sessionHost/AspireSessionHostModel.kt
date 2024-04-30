package model.sessionHost

import com.jetbrains.rd.generator.nova.*
import com.jetbrains.rd.generator.nova.PredefinedType.*
import com.jetbrains.rd.generator.nova.csharp.CSharp50Generator
import com.jetbrains.rd.generator.nova.kotlin.Kotlin11Generator

object AspireSessionHostRoot : Root() {
    init {
        setting(Kotlin11Generator.Namespace, "me.rafaelldi.aspire.generated")
        setting(CSharp50Generator.Namespace, "AspireSessionHost.Generated")
    }
}

@Suppress("unused")
object AspireSessionHostModel : Ext(AspireSessionHostRoot) {
    private val ProcessStarted = structdef {
        field("id", string)
        field("pid", long)
    }

    private val ProcessTerminated = structdef {
        field("id", string)
        field("exitCode", int)
    }

    private val LogReceived = structdef {
        field("id", string)
        field("isStdErr", bool)
        field("message", string)
    }

    private val SessionEnvironmentVariable = structdef {
        field("key", string)
        field("value", string)
    }

    private val SessionModel = structdef {
        field("projectPath", string)
        field("debug", bool)
        field("launchProfile", string.nullable)
        field("disableLaunchProfile", bool)
        field("args", array(string).nullable)
        field("envs", array(SessionEnvironmentVariable).nullable)
    }

    private val SessionCreationResult = structdef {
        field("sessionId", string)
    }

    private val ResourceWrapper = classdef {
        property("model", ResourceModel)
        property("isInitialized", bool).async
        sink("logReceived", ResourceLog)
        sink("metricReceived", ResourceMetric)
    }

    private val ResourceModel = structdef {
        field("name", string)
        field("type", enum("ResourceType") {
            +"Project"
            +"Container"
            +"Executable"
            +"Unknown"
        })
        field("displayName", string)
        field("uid", string)
        field("state",  enum("ResourceState") {
            +"Finished"
            +"Exited"
            +"FailedToStart"
            +"Starting"
            +"Running"
            +"Hidden"
            +"Unknown"
        }.nullable)
        field("stateStyle", enum("ResourceStateStyle") {
            +"Success"
            +"Info"
            +"Warning"
            +"Error"
            +"Unknown"
        }.nullable)
        field("createdAt", dateTime)
        field("properties", array(ResourceProperty))
        field("environment", array(ResourceEnvironmentVariable))
        field("urls", array(ResourceUrl))
    }

    private val ResourceProperty = structdef {
        field("name", string)
        field("displayName", string.nullable)
        field("value", string.nullable)
    }

    private val ResourceEnvironmentVariable = structdef {
        field("key", string)
        field("value", string.nullable)
    }

    private val ResourceUrl = structdef {
        field("name", string)
        field("fullUrl", string)
        field("isInternal", bool)
    }

    private val ResourceLog = structdef {
        field("text", string)
        field("isError", bool)
        field("lineNumber", int)
    }

    private val ResourceMetric = structdef {
        field("serviceName", string)
        field("scope", string)
        field("name", string)
        field("description", string.nullable)
        field("unit", string.nullable)
        field("value", double)
        field("timestamp", long)
    }

    private val TraceNode = structdef {
        field("id", string)
        field("name", string)
        field("serviceName", string.nullable)
        field("children", immutableList(TraceNodeChild))
        field("attributes", immutableList(TraceNodeAttribute))
    }

    private val TraceNodeChild = structdef {
        field("id", string)
        field("connectionCount", int)
    }

    private val TraceNodeAttribute = structdef {
        field("key", string)
        field("value", string)
    }

    private val RdOtelResource = structdef {
        field("serviceName", string)
        field("serviceInstanceId", string.nullable)
    }

    private val RdOtelResourceMetrics = structdef {
        field("resource", RdOtelResource)
        field("scopeMetrics", array(RdOtelScopeMetrics))
    }

    private val RdOtelScopeMetrics = structdef {
        field("scopeName", string)
        field("metrics", array(RdOtelMetric))
    }

    private val RdOtelMetric = structdef {
        field("name", string)
        field("description", string)
        field("unit", string)
        field("type", enum("RdOtelMetricType") {
            +"Gauge"
            +"Sum"
            +"Histogram"
            +"Unknown"
        })
    }

    init {
        callback("createSession", SessionModel, SessionCreationResult.nullable)
        callback("deleteSession", string, bool)

        source("processStarted", ProcessStarted)
        source("processTerminated", ProcessTerminated)
        source("logReceived", LogReceived)

        map("resources", string, ResourceWrapper)

        call("getTraceNodes", void, array(TraceNode))

        sink("metricReceived", RdOtelResourceMetrics)
    }
}