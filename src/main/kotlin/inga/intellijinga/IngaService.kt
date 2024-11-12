package inga.intellijinga

import com.esotericsoftware.kryo.kryo5.minlog.Log
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.PullImageResultCallback
import com.github.dockerjava.api.exception.DockerException
import com.github.dockerjava.api.model.*
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.LanguageServerWrapper
import com.redhat.devtools.lsp4ij.ServerStatus
import com.redhat.devtools.lsp4ij.lifecycle.LanguageServerLifecycleListener
import com.redhat.devtools.lsp4ij.lifecycle.LanguageServerLifecycleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.messages.Message
import org.java_websocket.WebSocket
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.pathString

@Service(Service.Level.PROJECT)
class IngaService(
    private val project: Project,
    private val cs: CoroutineScope
) {
    companion object {
        const val INGA_IMAGE_NAME = "ghcr.io/seachicken/inga"
        const val INGA_IMAGE_TAG = "0.24.4-beta.37-java"
        const val INGA_UI_IMAGE_NAME = "ghcr.io/seachicken/inga-ui"
        const val INGA_UI_IMAGE_TAG = "0.7.7-beta.12"
    }

    private val ingaContainerName = "inga_${project.name}"
    private val ingaUiContainerName = "inga-ui_${project.name}"
    private lateinit var client: DockerClient
    private var webSocketServer: IngaWebSocketServer? = null

    fun start(): String {
        Log.info("INGA starting Inga analysis...")
        if (!::client.isInitialized) {
            val config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .build()
            val httpClient = ApacheDockerHttpClient.Builder()
                .dockerHost(config.dockerHost)
                .build()
            client = DockerClientImpl.getInstance(config, httpClient)
        }

        return runBlocking {
            client.createVolumeCmd().withName(ingaContainerName).exec()
            cs.launch {
                val unusedPort = ServerSocket(0).use {
                    it.localPort
                }
                project.service<IngaSettings>().webSocketPort = unusedPort
                webSocketServer = IngaWebSocketServer(unusedPort, project)
                webSocketServer?.start()

                startIngaUiContainer()
            }
            startIngaContainer(project.service<IngaSettings>().state)
        }
    }

    fun stop() {
        Log.info("INGA stop Inga analysis")
        if (!::client.isInitialized) {
            throw IllegalStateException("Inga analysis is not running")
        }

        runBlocking {
            cs.launch {
                webSocketServer?.stop()
                stopContainer(ingaUiContainerName)
            }
            stopContainer(ingaContainerName)
        }
    }

    fun clearCachesAndRestart() {
        fun clearCachesAndStart() {
            client
                .listContainersCmd()
                .withShowAll(true)
                .exec()
                .find(isTargetContainer(ingaContainerName))
                ?.let {
                    client.removeContainerCmd(it.id).exec()
                    removeImageIfUnused(it.image)
                }
            client
                .listContainersCmd()
                .withShowAll(true)
                .exec()
                .find(isTargetContainer(ingaUiContainerName))
                ?.let {
                    client.removeContainerCmd(it.id).exec()
                    removeImageIfUnused(it.image)
                }
            client.removeVolumeCmd(ingaContainerName).exec()
            LanguageServerManager.getInstance(project).start("ingaLanguageServer")
        }

        if (LanguageServerManager.getInstance(project).getServerStatus("ingaLanguageServer") == ServerStatus.stopped) {
            clearCachesAndStart()
        } else {
            LanguageServerLifecycleManager.getInstance(project)
                .addLanguageServerLifecycleListener(object : LanguageServerLifecycleListener {
                    private var started = false
                    private var retryCount = 0
                    private var maxCount = 3
                    override fun handleStatusChanged(server: LanguageServerWrapper?) {
                        if (!started && server?.serverStatus == ServerStatus.stopped) {
                            started = true
                            clearCachesAndStart()
                            LanguageServerLifecycleManager.getInstance(project).removeLanguageServerLifecycleListener(this)
                        }
                    }

                    override fun handleLSPMessage(message: Message?, consumer: MessageConsumer?, server: LanguageServerWrapper?) {
                    }

                    override fun handleError(server: LanguageServerWrapper?, e: Throwable?) {
                        retryCount++
                        Log.warn("INGA restart failed. retry: ${retryCount}/${maxCount}", e)
                        if (retryCount <= maxCount) {
                            LanguageServerManager.getInstance(project).stop("ingaLanguageServer")
                            return
                        }
                        LanguageServerLifecycleManager.getInstance(project).removeLanguageServerLifecycleListener(this)
                    }

                    override fun dispose() {
                    }
                })
            LanguageServerManager.getInstance(project).stop("ingaLanguageServer")
        }
    }

    private fun startIngaContainer(state: IngaSettingsState): String {
        var ingaContainer = client
            .listContainersCmd()
            .withShowAll(true)
            .exec()
            .find(isTargetContainer(ingaContainerName))

        fun pullNewImage() {
            client
                .pullImageCmd(INGA_IMAGE_NAME)
                .withTag(INGA_IMAGE_TAG)
                .withPlatform("linux/amd64")
                .exec(object : PullImageResultCallback() {
                    override fun onNext(item: PullResponseItem?) {
                        Log.info("INGA ${item?.status}")
                        super.onNext(item)
                    }
                }).awaitCompletion()
        }

        if (ingaContainer == null) {
            try {
                pullNewImage()
            } catch (e: DockerException) {
                throw IllegalStateException("image pull failed ", e)
            }
        } else {
            if (ingaContainer.state == "running") {
                stopContainer(ingaContainerName)
            }

            fun hasNewImage(container: Container) =
                container.image == "$INGA_IMAGE_NAME:$INGA_IMAGE_TAG"

            fun pullAndRemoveIfNewImagePulled(oldContainer: Container): Container? {
                try {
                    pullNewImage()
                } catch (e: DockerException) {
                    Log.warn("INGA failed to pull new image", e)
                    return oldContainer
                }

                client.removeContainerCmd(oldContainer.id).exec()
                if (!hasNewImage(oldContainer)) {
                    removeImageIfUnused(oldContainer.image)
                }
                return null
            }

            if (!hasNewImage(ingaContainer)
                || state.ingaContainerParameters != state.ingaUserParameters
            ) {
                ingaContainer = pullAndRemoveIfNewImagePulled(ingaContainer)
            }
        }

        return if (ingaContainer == null) {
            createIngaContainer(state)
        } else {
            ingaContainer.id
        }.also {
            client.startContainerCmd(it).exec()
        }
    }

    private fun createIngaContainer(state: IngaSettingsState): String {
        val command = mutableListOf(
            "--mode", "server", "--root-path", "/work", "--output-path", "/inga-output", "--temp-path", "/inga-temp",
        )
        if (state.ingaUserParameters.includePathPattern.isNotEmpty()) {
            command += "--include"
            command += state.ingaUserParameters.includePathPattern
        }
        if (state.ingaUserParameters.excludePathPattern.isNotEmpty()) {
            command += "--exclude"
            command += state.ingaUserParameters.excludePathPattern
        }

        val binds = mutableListOf(
            Bind(project.basePath, Volume("/work"), AccessMode.ro),
            Bind(ingaContainerName, Volume("/inga-output"), AccessMode.rw)
        )
        val gradleHome = Paths.get(System.getProperty("user.home")).resolve(".gradle")
        if (Files.exists(gradleHome)) {
            binds.add(Bind(gradleHome.pathString, Volume("/root/.gradle"), AccessMode.ro))
        }
        val mavenHome = Paths.get(System.getProperty("user.home")).resolve(".m2")
        if (Files.exists(mavenHome)) {
            binds.add(Bind(mavenHome.pathString, Volume("/root/.m2"), AccessMode.ro))
        }
        for ((src, dst) in state.ingaUserParameters.additionalMounts) {
            binds.add(Bind(src, Volume(dst), AccessMode.ro))
        }

        return client
            .createContainerCmd("$INGA_IMAGE_NAME:$INGA_IMAGE_TAG")
            .withName(ingaContainerName)
            .withStdinOpen(true)
            .withPlatform("linux/amd64")
            .withHostConfig(
                HostConfig.newHostConfig()
                    .withBinds(binds)
                    .withTmpFs(mapOf("/inga-temp" to "rw,noexec"))
            )
            .withWorkingDir("/work")
            .withCmd(command)
            .exec()
            .id.also {
                state.ingaContainerParameters = state.ingaUserParameters
            }
    }

    private fun startIngaUiContainer(): String {
        var ingaUiContainer = client
            .listContainersCmd()
            .withShowAll(true)
            .exec()
            .find(isTargetContainer(ingaUiContainerName))

        fun pullNewImage() {
            client
                .pullImageCmd(INGA_UI_IMAGE_NAME)
                .withTag(INGA_UI_IMAGE_TAG)
                .exec(object : PullImageResultCallback() {
                    override fun onNext(item: PullResponseItem?) {
                        Log.info("INGA-UI ${item?.status}")
                        super.onNext(item)
                    }
                }).awaitCompletion()
        }

        if (ingaUiContainer == null) {
            try {
                pullNewImage()
            } catch (e: DockerException) {
                throw IllegalStateException("image pull failed ", e)
            }
        } else {
            if (ingaUiContainer.state == "running") {
                stopContainer(ingaUiContainerName)
            }

            fun hasNewImage(container: Container) =
                container.image == "$INGA_UI_IMAGE_NAME:$INGA_UI_IMAGE_TAG"

            fun pullAndRemoveIfNewImagePulled(oldContainer: Container): Container? {
                try {
                    pullNewImage()
                } catch (e: DockerException) {
                    Log.warn("INGA-UI failed to pull new image", e)
                    return oldContainer
                }

                client.removeContainerCmd(oldContainer.id).exec()
                if (!hasNewImage(oldContainer)) {
                    removeImageIfUnused(oldContainer.image)
                }
                return null
            }

            if (!hasNewImage(ingaUiContainer)) {
                ingaUiContainer = pullAndRemoveIfNewImagePulled(ingaUiContainer)
            }
        }

        return if (ingaUiContainer == null) {
            val unusedPort = ServerSocket(0).use {
                it.localPort
            }
            project.service<IngaSettings>().serverPort = unusedPort
            val exposedPort = ExposedPort(unusedPort)

            client
                .createContainerCmd("$INGA_UI_IMAGE_NAME:$INGA_UI_IMAGE_TAG")
                .withName(ingaUiContainerName)
                .withHostConfig(
                    HostConfig.newHostConfig()
                        .withBinds(
                            Bind(ingaContainerName, Volume("/html/report"), AccessMode.ro)
                        )
                        .withPortBindings(PortBinding(Ports.Binding.bindPort(unusedPort), exposedPort))
                        // add to terminate httpd with sigterm
                        .withInit(true)
                )
                .withExposedPorts(exposedPort)
                .withCmd("$unusedPort")
                .exec()
                .id
        } else {
            project.service<IngaSettings>().serverPort = ingaUiContainer.command.split(" ").last().toIntOrNull()
            ingaUiContainer.id
        }.also {
            client.startContainerCmd(it).exec()
        }
    }

    private fun stopContainer(containerName: String) {
        client
            .listContainersCmd()
            .exec()
            .find(isTargetContainer(containerName))
            ?.let {
                client.stopContainerCmd(it.id).exec()
            }
    }

    private fun removeImageIfUnused(imageName: String) {
        val usedContainers = client
            .listContainersCmd()
            .withShowAll(true)
            .exec()
            .filter { it.image.contains(imageName) }
        if (usedContainers.isEmpty()) {
            client.removeImageCmd(imageName).exec()
        }
    }

    private fun isTargetContainer(name: String): (Container) -> Boolean =
        { it.names[0].substringAfter("/") == name }
}