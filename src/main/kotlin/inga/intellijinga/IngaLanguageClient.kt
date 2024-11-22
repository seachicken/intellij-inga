package inga.intellijinga

import com.google.gson.Gson
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileEvent
import com.intellij.openapi.vfs.VirtualFileListener
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.impl.BulkVirtualFileListenerAdapter
import com.intellij.util.messages.MessageBusConnection
import com.redhat.devtools.lsp4ij.ServerStatus
import com.redhat.devtools.lsp4ij.client.IndexAwareLanguageClient
import com.redhat.devtools.lsp4ij.commands.CommandExecutor
import com.redhat.devtools.lsp4ij.commands.LSPCommandContext
import org.eclipse.lsp4j.Command
import java.nio.file.Path

class IngaLanguageClient(project: Project) : IndexAwareLanguageClient(project), VirtualFileListener {
    private val connection: MessageBusConnection = project.messageBus.connect()
    private var isServerStarted = false

    init {
        connection.subscribe(VirtualFileManager.VFS_CHANGES, BulkVirtualFileListenerAdapter(this))
    }

    override fun dispose() {
        super.dispose()
        connection.disconnect()
    }

    override fun handleServerStatusChanged(serverStatus: ServerStatus?) {
        if (serverStatus == ServerStatus.started) {
            isServerStarted = true

            (languageServer as? IngaLanguageServerApi)?.diffChanged(
                DiffChanged(
                    gitDiff(project.service<IngaSettings>().state.ingaUserParameters.baseBranch)
                )
            )

            val gson = Gson()
            LSPCommandContext(Command("inga.getModulePaths", "inga.getModulePaths"), project).apply {
                preferredLanguageServerId = "ingaLanguageServer";
            }.also {
                CommandExecutor
                    .executeCommand(it)
                    .response()
                    ?.thenAccept { r ->
                        project.service<IngaSettings>().modulePaths =
                            gson.fromJson(gson.toJson(r), GetModulePathsResponse::class.java).modulePaths
                                .filter { p ->  p.isNotEmpty() }
                    }
            }

            LSPCommandContext(Command("inga.getConfig", "inga.getConfig"), project).apply {
                preferredLanguageServerId = "ingaLanguageServer";
            }.also {
                CommandExecutor
                    .executeCommand(it)
                    .response()
                    ?.thenAccept { r ->
                        project.service<IngaSettings>().config = gson.fromJson(gson.toJson(r), IngaConfig::class.java)
                    }
            }
        }
    }

    override fun contentsChanged(event: VirtualFileEvent) {
        if (!isServerStarted) {
            return
        }

        (languageServer as? IngaLanguageServerApi)?.diffChanged(
            DiffChanged(
                gitDiff(project.service<IngaSettings>().state.ingaUserParameters.baseBranch),
                event.file.url
            )
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

data class GetModulePathsResponse(val modulePaths: List<String>)
