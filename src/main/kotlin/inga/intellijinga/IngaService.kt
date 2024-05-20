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
import java.nio.file.Paths
import kotlin.io.path.pathString

@Service(Service.Level.PROJECT)
class IngaService(private val project: Project) {
    companion object {
        const val INGA_IMAGE_NAME = "ghcr.io/seachicken/inga"
        const val INGA_IMAGE_TAG = "latest-java"
        const val INGA_UI_IMAGE_NAME = "ghcr.io/seachicken/inga-ui"
        const val INGA_UI_IMAGE_TAG = "latest"
    }

    private val ingaContainerName = "inga_${project.name}"
    private val ingaUiContainerName = "inga-ui_${project.name}"
    private val ingaTempPath = Paths.get(PathManager.getPluginsPath(), "intellij-inga", ingaContainerName)
    private lateinit var client: DockerClient

    fun start(): String {
        Log.info("starting Inga analysis...")
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
                project.service<IngaSettings>().state.ingaUiContainerId =
                    startIngaUiContainer(project.service<IngaSettings>().state)
            }
            startIngaContainer(project.service<IngaSettings>().state).also {
                project.service<IngaSettings>().state.ingaContainerId = it
            }
        }
    }

    fun stop() {
        Log.info("stop Inga analysis")
        if (!::client.isInitialized) {
            throw IllegalStateException("Inga analysis is not running")
        }

        runBlocking {
            launch {
                val containerId = project.service<IngaSettings>().state.ingaContainerId
                stopContainer(containerId)
            }
            launch {
                val containerId = project.service<IngaSettings>().state.ingaUiContainerId
                stopContainer(containerId)
            }
        }
    }

    private fun startIngaContainer(state: IngaSettingsState): String {
        var ingaContainer = client
            .listContainersCmd()
            .withShowAll(true)
            .exec()
            .find { it.names[0].substringAfter("/") == ingaContainerName }

        if (ingaContainer != null && state.ingaContainerParameters != state.ingaUserParameters) {
            if (ingaContainer.state == "running") {
                stopContainer(ingaContainer.id)
            }
            client
                .removeContainerCmd(ingaContainer.id)
                .exec()
            ingaContainer = null
        }

        return if (ingaContainer == null) {
            client
                .pullImageCmd(INGA_IMAGE_NAME)
                .withTag(INGA_IMAGE_TAG)
                .withPlatform("linux/amd64")
                .exec(object : PullImageResultCallback() {
                    override fun onNext(item: PullResponseItem?) {
                        Log.info(item?.status)
                        super.onNext(item)
                    }
                }).awaitCompletion()
            createIngaContainer(state)
        } else {
            ingaContainer.id
        }.also {
            if (ingaContainer?.state == "running") {
                stopContainer(it)
            }
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

    private fun startIngaUiContainer(state: IngaSettingsState): String {
        var ingaUiContainer = client
            .listContainersCmd()
            .withShowAll(true)
            .exec()
            .find { it.names[0].substringAfter("/") == ingaUiContainerName }

        if (ingaUiContainer != null && state.ingaUiContainerParameters != state.ingaUiUserParameters) {
            if (ingaUiContainer.state == "running") {
                stopContainer(ingaUiContainer.id)
            }
            client
                .removeContainerCmd(ingaUiContainer.id)
                .exec()
            ingaUiContainer = null
        }

        return if (ingaUiContainer == null) {
            client
                .pullImageCmd(INGA_UI_IMAGE_NAME)
                .withTag(INGA_UI_IMAGE_TAG)
                .exec(object : PullImageResultCallback() {
                    override fun onNext(item: PullResponseItem?) {
                        Log.info(item?.status)
                        super.onNext(item)
                    }
                }).awaitCompletion()
            val exposedPort = ExposedPort(state.ingaUiUserParameters.port)
            client
                .createContainerCmd("$INGA_UI_IMAGE_NAME:$INGA_UI_IMAGE_TAG")
                .withName(ingaUiContainerName)
                .withHostConfig(
                    HostConfig.newHostConfig()
                        .withBinds(
                            Bind("${ingaTempPath.pathString}/report", Volume("/inga-ui/inga-report"), AccessMode.rw)
                        )
                        .withPortBindings(PortBinding(Ports.Binding.bindPort(state.ingaUiUserParameters.port), exposedPort))
                )
                .withEntrypoint("bash")
                .withCmd("-c", "npm run build && npm run preview -- --port ${state.ingaUiUserParameters.port}")
                .withExposedPorts(exposedPort)
                .exec()
                .id.also {
                    state.ingaUiContainerParameters = state.ingaUiUserParameters
                }
        } else {
            ingaUiContainer.id
        }.also {
            if (ingaUiContainer?.state == "running") {
                stopContainer(it)
            }
            client.startContainerCmd(it).exec()
        }
    }

    private fun stopContainer(containerId: String) {
        client
            .listContainersCmd()
            .exec()
            .find { it.id == containerId }
            .let {
                client.stopContainerCmd(containerId).exec()
            }
    }
}