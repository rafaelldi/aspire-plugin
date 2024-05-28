package me.rafaelldi.aspire.services

import com.intellij.util.messages.Topic

interface ResourceListener {
    companion object {
        @Topic.ProjectLevel
        val TOPIC = Topic.create("Aspire Resource Listener", ResourceListener::class.java)
    }

    fun resourceCreated(resource: AspireResource)
    fun resourceUpdated(resource: AspireResource)
}