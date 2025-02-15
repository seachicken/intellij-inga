package inga.intellijinga

import com.google.gson.Gson
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileEvent
import com.intellij.openapi.vfs.VirtualFileListener
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.impl.BulkVirtualFileListenerAdapter
import com.intellij.util.messages.MessageBusConnection
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.ServerStatus
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.commands.CommandExecutor
import com.redhat.devtools.lsp4ij.commands.LSPCommandContext
import com.redhat.devtools.lsp4ij.server.OSProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.services.LanguageServer
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

class IngaLanguageServerFactory : LanguageServerFactory {
    private lateinit var fileListener: VirtualFileListener
    private lateinit var messageBusConnection: MessageBusConnection

    override fun createConnectionProvider(project: Project): StreamConnectionProvider {
        return object : OSProcessStreamConnectionProvider() {
            override fun start() {
                val ingaContainerId = project.service<IngaService>().start()
                commandLine = GeneralCommandLine("docker", "attach", ingaContainerId)
                super.start()

                messageBusConnection = project.messageBus.connect()
                messageBusConnection.subscribe(VirtualFileManager.VFS_CHANGES, BulkVirtualFileListenerAdapter(fileListener))
            }

            override fun stop() {
                super.stop()
                messageBusConnection.disconnect()
                project.service<IngaService>().stop()
            }
        }
    }

    override fun createClientFeatures(): LSPClientFeatures {
        val clientFeatures = object : LSPClientFeatures() {
            override fun keepServerAlive(): Boolean {
                return true
            }

            override fun handleServerStatusChanged(serverStatus: ServerStatus) {
                if (serverStatus == ServerStatus.started) {
                    gitDiff(
                        project.service<IngaSettings>().state.ingaUserParameters.baseBranch,
                        project
                    ).thenAccept {
                        (languageServer as IngaLanguageServerApi).diffChanged(DiffChanged(it))
                    }

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
        }

        fileListener = object : VirtualFileListener {
            override fun contentsChanged(event: VirtualFileEvent) {
                gitDiff(
                    clientFeatures.project.service<IngaSettings>().state.ingaUserParameters.baseBranch,
                    clientFeatures.project
                ).thenAccept {
                    (clientFeatures.languageServer as IngaLanguageServerApi).diffChanged(DiffChanged(it, event.file.url))
                }
            }
        }

        return clientFeatures
    }

    override fun getServerInterface(): Class<out LanguageServer> {
        return IngaLanguageServerApi::class.java
    }

    private fun gitDiff(baseBranch: String, project: Project): CompletableFuture<String> {
        val commands = mutableListOf("git", "diff")
        if (baseBranch.isNotEmpty()) {
            commands.add(baseBranch)
        }
        commands += "--unified=0"
        commands += "--"
        return CompletableFuture.supplyAsync {
            val process = ProcessBuilder(commands)
                .directory(project.basePath?.let { Path.of(it).toFile() })
                .start()
            process.waitFor()
            process.inputReader().readText()
        }
    }

    data class GetModulePathsResponse(val modulePaths: List<String>)
}