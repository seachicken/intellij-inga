package inga.intellijinga

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.EnvironmentUtil
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.server.ProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import java.io.File
import java.nio.file.Paths
import kotlin.io.path.pathString

class IngaLanguageServer : LanguageServerFactory {
    override fun createConnectionProvider(project: Project): StreamConnectionProvider {
        return object : ProcessStreamConnectionProvider() {
            override fun start() {
                val ingaContainerId = project.service<IngaService>().start()
                commands = listOf(
                    // ProcessBuilder.environment() does not work at release, getting the docker full path
                    findDocker(),
                    "attach", ingaContainerId)
                super.start()
            }

            override fun stop() {
                project.service<IngaService>().stop()
                super.stop()
            }
        }
    }

    fun findDocker(): String =
        EnvironmentUtil.getEnvironmentMap().values
            .flatMap { it.split(File.pathSeparator) }
            .map { File(Paths.get(it, "docker").pathString) }
            .find { it.exists() && it.canExecute() }
            ?.path ?: throw IllegalStateException("docker not found on the system")
}