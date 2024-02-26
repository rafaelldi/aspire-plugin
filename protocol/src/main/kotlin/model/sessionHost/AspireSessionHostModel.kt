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
        field("id", string)
        field("projectPath", string)
        field("debug", bool)
        field("launchProfile", string.nullable)
        field("disableLaunchProfile", bool)
        field("args", array(string).nullable)
        field("envs", array(SessionEnvironmentVariable).nullable)
    }

    private val ResourceWrapper = classdef {
        property("model", ResourceModel)
        property("isInitialized", bool)
        sink("logReceived", ResourceLog)
        sink("metricReceived", ResourceMetric)
    }

    private val ResourceModel = structdef {
        field("name", string)
        field("resourceType", enum("ResourceType") {
            +"Project"
            +"Container"
            +"Executable"
            +"Unknown"
        })
        field("displayName", string)
        field("uid", string)
        field("state", string.nullable)
        field("createdAt", dateTime)
        field("expectedEndpointsCount", int.nullable)
        field("properties", array(ResourceProperty))
        field("environment", array(ResourceEnvironmentVariable))
        field("endpoints", array(ResourceEndpoint))
        field("services", array(ResourceService))
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

    private val ResourceEndpoint = structdef {
        field("endpointUrl", string)
        field("proxyUrl", string)
    }

    private val ResourceService = structdef {
        field("name", string)
        field("allocatedAddress", string.nullable)
        field("allocatedPort", int.nullable)
    }

    private val ResourceLog = structdef {
        field("text", string)
        field("isError", bool)
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

    init {
        map("sessions", string, SessionModel)

        source("processStarted", ProcessStarted)
        source("processTerminated", ProcessTerminated)
        source("logReceived", LogReceived)

        map("resources", string, ResourceWrapper)

        call("getTraceNodes", void, array(TraceNode))
    }
}