package inga.intellijinga

import com.esotericsoftware.kryo.kryo5.minlog.Log
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.server.ProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import org.eclipse.lsp4j.jsonrpc.messages.Message
import org.eclipse.lsp4j.services.LanguageServer
import java.net.URI

class IngaLanguageServer : LanguageServerFactory {
    override fun createConnectionProvider(p: Project): StreamConnectionProvider {
        return object : ProcessStreamConnectionProvider() {
            override fun start() {
                p.service<IngaService>().start()
                commands = listOf("docker", "attach", p.service<IngaService>().ingaContainerId)
                super.start()
            }

            override fun stop() {
                p.service<IngaService>().stop()
                super.stop()
            }

            override fun handleMessage(message: Message?, languageServer: LanguageServer?, rootUri: URI?) {
                Log.info("handle message: $message, rootUri: $rootUri")
            }
        }
    }
}