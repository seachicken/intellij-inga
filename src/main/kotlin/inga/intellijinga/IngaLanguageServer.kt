package inga.intellijinga

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.server.ProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider

class IngaLanguageServer : LanguageServerFactory {
    override fun createConnectionProvider(p: Project): StreamConnectionProvider {
        return object : ProcessStreamConnectionProvider() {
            override fun start() {
                p.service<IngaService>().start()
                commands = listOf("docker", "attach", p.service<IngaService>().containerId)
                super.start()
            }

            override fun stop() {
                p.service<IngaService>().stop()
                super.stop()
            }
        }
    }
}