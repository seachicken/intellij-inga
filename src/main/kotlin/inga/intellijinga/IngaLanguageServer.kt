package inga.intellijinga

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.client.LanguageClientImpl
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.server.OSProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import org.eclipse.lsp4j.services.LanguageServer

class IngaLanguageServer : LanguageServerFactory {
    override fun createConnectionProvider(project: Project): StreamConnectionProvider {
        return object : OSProcessStreamConnectionProvider() {
            override fun start() {
                val ingaContainerId = project.service<IngaService>().start()
                commandLine = GeneralCommandLine("docker", "attach", ingaContainerId)
                super.start()
            }

            override fun stop() {
                super.stop()
                project.service<IngaService>().stop()
            }
        }
    }

    override fun createLanguageClient(project: Project): LanguageClientImpl {
        return IngaLanguageClient(project)
    }

    override fun createClientFeatures(): LSPClientFeatures {
        return object : LSPClientFeatures() {
            override fun keepServerAlive(): Boolean {
                return true
            }
        }
    }

    override fun getServerInterface(): Class<out LanguageServer> {
        return IngaLanguageServerApi::class.java
    }
}