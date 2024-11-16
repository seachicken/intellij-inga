package inga.intellijinga

import com.esotericsoftware.kryo.kryo5.minlog.Log
import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.redhat.devtools.lsp4ij.commands.CommandExecutor
import com.redhat.devtools.lsp4ij.commands.LSPCommandContext
import org.eclipse.lsp4j.Command
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.file.Paths
import kotlin.io.path.pathString

class IngaWebSocketServer(
    port: Int,
    private val project: Project
) : WebSocketServer(InetSocketAddress(port)) {
    override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
    }

    override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
    }

    override fun onMessage(conn: WebSocket?, message: String?) {
        if (message == null) {
            return
        }

        val gson = Gson()
        when (gson.fromJson(message, BaseMessage::class.java).method) {
            "getConnectionPaths" -> {
                val getRequest = gson.fromJson(message, GetConnectionPathsRequest::class.java)
                conn?.send(
                    gson.toJson(
                        GetConnectionPathsResponse(
                            project.service<IngaSettings>().modulePaths,
                            project.service<IngaSettings>().getCallerHint(getRequest.serverPath)
                        )
                    )
                )
            }
            "addConnectionPaths" -> {
                val addRequest = gson.fromJson(message, AddConnectionPathsRequest::class.java)
                val command = Command(
                    "inga.updateConfig", "inga.updateConfig", listOf(
                        project.service<IngaSettings>().applyRequest(addRequest).config
                    )
                )
                LSPCommandContext(command, project).apply {
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
            "openFile" -> {
                val openRequest = gson.fromJson(message, OpenFileRequest::class.java)
                val file = LocalFileSystem.getInstance()
                    .findFileByPath(Paths.get(project.basePath ?: "", openRequest.path).pathString)
                if (file != null) {
                    ApplicationManager.getApplication().invokeLater {
                        OpenFileDescriptor(project, file, openRequest.line - 1, openRequest.offset - 1).navigate(true)
                        FileEditorManager.getInstance(project).openFile(file, true)
                    }
                }
            }
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {
        Log.error("INGA WebSocket error", ex)
    }

    override fun onStart() {
    }
}

open class BaseMessage(val method: String)

data class GetConnectionPathsResponse(
    val modulePaths: List<String>,
    val callerHint: List<Client>?
) : BaseMessage("getConnectionPaths")

data class GetConnectionPathsRequest(
    val serverPath: String
) : BaseMessage("getConnectionPaths")

data class AddConnectionPathsRequest(
    val serverPath: String,
    val clientPaths: List<String>?
) : BaseMessage("addConnectionPaths")

data class OpenFileRequest(
    val path: String,
    val line: Int,
    val offset: Int
) : BaseMessage("getConnectionPaths")
