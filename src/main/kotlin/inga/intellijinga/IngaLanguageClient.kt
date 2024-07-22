package inga.intellijinga

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileEvent
import com.intellij.openapi.vfs.VirtualFileListener
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.impl.BulkVirtualFileListenerAdapter
import com.intellij.util.messages.MessageBusConnection
import com.redhat.devtools.lsp4ij.ServerStatus
import com.redhat.devtools.lsp4ij.client.IndexAwareLanguageClient
import java.nio.file.Path

class IngaLanguageClient(project: Project) : IndexAwareLanguageClient(project), VirtualFileListener {
    private val connection: MessageBusConnection = project.messageBus.connect()

    init {
        connection.subscribe(VirtualFileManager.VFS_CHANGES, BulkVirtualFileListenerAdapter(this))
    }

    override fun dispose() {
        super.dispose()
        connection.disconnect()
    }

    override fun handleServerStatusChanged(serverStatus: ServerStatus?) {
        if (serverStatus == ServerStatus.started) {
            (languageServer as? IngaLanguageServerApi)?.diffChanged(
                gitDiff(project.service<IngaSettings>().state.ingaUserParameters.baseBranch)
            )
        }
    }

    override fun contentsChanged(event: VirtualFileEvent) {
        (languageServer as? IngaLanguageServerApi)?.diffChanged(
            gitDiff(project.service<IngaSettings>().state.ingaUserParameters.baseBranch)
        )
    }

    private fun gitDiff(baseBranch: String): String {
        val commands = mutableListOf("git", "diff")
        if (baseBranch.isNotEmpty()) {
            commands.add(baseBranch)
        }
        commands += "--unified=0"
        commands += "--"
        val process = ProcessBuilder(commands)
            .directory(project.basePath?.let { Path.of(it).toFile() })
            .start()
        process.waitFor()
        return process.inputReader().readText()
    }
}