package inga.intellijinga

import com.esotericsoftware.kryo.kryo5.minlog.Log
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.PullImageResultCallback
import com.github.dockerjava.api.model.*
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.pathString

@Service(Service.Level.PROJECT)
class IngaService(private val project: Project) {
    companion object {
        const val INGA_IMAGE_NAME = "ghcr.io/seachicken/inga"
        const val INGA_IMAGE_TAG = "0.13.13-java"
        const val INGA_UI_IMAGE_NAME = "ghcr.io/seachicken/inga-ui"
        const val INGA_UI_IMAGE_TAG = "0.1.15"
    }

    private val ingaContainerName = "inga_${project.name}"
    private val ingaUiContainerName = "inga-ui_${project.name}"
    private val ingaTempPath = Paths.get(PathManager.getPluginsPath(), "intellij-inga", ingaContainerName)
    private lateinit var client: DockerClient

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
            launch {
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
            launch {
                stopContainer(ingaContainerName)
            }
            launch {
                stopContainer(ingaUiContainerName)
            }
        }
    }

    private fun startIngaContainer(state: IngaSettingsState): String {
        var ingaContainer = client
            .listContainersCmd()
            .withShowAll(true)
            .exec()
            .find { it.names[0].substringAfter("/") == ingaContainerName }

        if (ingaContainer != null) {
            if (ingaContainer.state == "running") {
                stopContainer(ingaContainerName)
            }

            if (ingaContainer.image != "$INGA_IMAGE_NAME:$INGA_IMAGE_TAG"
                || state.ingaContainerParameters != state.ingaUserParameters
            ) {
                client.removeContainerCmd(ingaContainer.id).exec()

                if (ingaContainer.image != "$INGA_IMAGE_NAME:$INGA_IMAGE_TAG") {
                    client.removeImageCmd(ingaContainer.image).exec()
                }

                ingaContainer = null
            }
        }

        return if (ingaContainer == null) {
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
            createIngaContainer(state)
        } else {
            ingaContainer.id
        }.also {
            client.startContainerCmd(it).exec()
        }
    }

    private fun createIngaContainer(state: IngaSettingsState): String {
        val command = mutableListOf(
            "--mode", "server", "--root-path", "/work", "--temp-path", "/inga-temp",
        )
        if (state.ingaUserParameters.baseBranch.isNotEmpty()) {
            command += "--base-commit"
            command += state.ingaUserParameters.baseBranch
        }
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
            Bind(ingaTempPath.pathString, Volume("/inga-temp"), AccessMode.rw)
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
            .withHostConfig(HostConfig.newHostConfig().withBinds(binds))
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
            .find { it.names[0].substringAfter("/") == ingaUiContainerName }

        if (ingaUiContainer != null) {
            if (ingaUiContainer.state == "running") {
                stopContainer(ingaUiContainerName)
            }

            client.removeContainerCmd(ingaUiContainer.id).exec()

            if (ingaUiContainer.image != "$INGA_UI_IMAGE_NAME:$INGA_UI_IMAGE_TAG") {
                client.removeImageCmd(ingaUiContainer.image).exec()
            }
        }

        client
            .pullImageCmd(INGA_UI_IMAGE_NAME)
            .withTag(INGA_UI_IMAGE_TAG)
            .exec(object : PullImageResultCallback() {
                override fun onNext(item: PullResponseItem?) {
                    Log.info("INGA-UI ${item?.status}")
                    super.onNext(item)
                }
            }).awaitCompletion()

        val unusedPort = ServerSocket(0).use {
            it.localPort
        }
        val exposedPort = ExposedPort(unusedPort)

        val containerId = client
            .createContainerCmd("$INGA_UI_IMAGE_NAME:$INGA_UI_IMAGE_TAG")
            .withName(ingaUiContainerName)
            .withHostConfig(
                HostConfig.newHostConfig()
                    .withBinds(
                        Bind("${ingaTempPath.pathString}/report", Volume("/inga-ui/inga-report"), AccessMode.rw)
                    )
                    .withPortBindings(PortBinding(Ports.Binding.bindPort(unusedPort), exposedPort))
            )
            .withEntrypoint("bash")
            .withCmd("-c", "npm run build && npm run preview -- --port $unusedPort")
            .withExposedPorts(exposedPort)
            .exec()
            .id

        client.startContainerCmd(containerId).exec()

        project.service<IngaSettings>().serverPort = unusedPort
        return containerId
    }

    private fun stopContainer(containerName: String) {
        client
            .listContainersCmd()
            .exec()
            .find { it.names[0].substringAfter("/") == containerName }
            ?.let {
                client.stopContainerCmd(it.id).exec()
            }
    }
}