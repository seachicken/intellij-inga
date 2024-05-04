package inga.intellijinga

import com.esotericsoftware.kryo.kryo5.minlog.Log
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.PullImageResultCallback
import com.github.dockerjava.api.model.*
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@Service(Service.Level.PROJECT)
class IngaService(private val project: Project) {
    companion object {
        const val INGA_IMAGE_NAME = "ghcr.io/seachicken/inga"
        const val INGA_IMAGE_TAG = "latest-java"
        const val INGA_UI_IMAGE_NAME = "ghcr.io/seachicken/inga-ui"
        const val INGA_UI_IMAGE_TAG = "latest"
    }

    private lateinit var client: DockerClient

    fun start() {
        Log.info("starting Inga analysis...")
        if (!::client.isInitialized) {
            val config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .build()
            val httpClient = ApacheDockerHttpClient.Builder()
                .dockerHost(config.dockerHost)
                .build()
            client = DockerClientImpl.getInstance(config, httpClient)
        }

        runBlocking {
            launch {
                val containerId = project.service<IngaSettings>().state.ingaContainerId
                project.service<IngaSettings>().state.ingaContainerId = startIngaContainer(containerId)
            }
            launch {
                val containerId = project.service<IngaSettings>().state.ingaUiContainerId
                project.service<IngaSettings>().state.ingaUiContainerId = startIngaUiContainer(containerId)
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

    private fun startIngaContainer(containerId: String): String {
        val ingaContainer = client
            .listContainersCmd()
            .withShowAll(true)
            .exec()
            .find { it.id == containerId }

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
            createIngaContainer()
        } else {
            ingaContainer.id
        }.also {
            if (ingaContainer?.state == "running") {
                stopContainer(it)
            }
            client.startContainerCmd(it).exec()
        }
    }

    private fun createIngaContainer(): String {
        val state = project.service<IngaSettings>().state
        val command = mutableListOf(
            "--mode", "server", "--root-path", "/work",
        )
        if (state.baseBranch.isNotEmpty()) {
            command += "--base-commit"
            command += state.baseBranch
        }
        if (state.includePathPattern.isNotEmpty()) {
            command += "--include"
            command += state.includePathPattern
        }
        if (state.excludePathPattern.isNotEmpty()) {
            command += "--exclude"
            command += state.excludePathPattern
        }

        return client
            .createContainerCmd("$INGA_IMAGE_NAME:$INGA_IMAGE_TAG")
            .withName("inga_${project.name}")
            .withStdinOpen(true)
            .withPlatform("linux/amd64")
            .withHostConfig(
                HostConfig.newHostConfig()
                    .withBinds(Bind(project.basePath, Volume("/work")))
            )
            .withWorkingDir("/work")
            .withCmd(command)
            .exec()
            .id
    }

    private fun startIngaUiContainer(containerId: String): String {
        val ingaUiContainer = client
            .listContainersCmd()
            .withShowAll(true)
            .exec()
            .find { it.id == containerId }

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
            val exposedPort = ExposedPort(4173)
            client
                .createContainerCmd("$INGA_UI_IMAGE_NAME:$INGA_UI_IMAGE_TAG")
                .withName("inga-ui_${project.name}")
                .withHostConfig(
                    HostConfig.newHostConfig()
                        .withBinds(Bind("${project.basePath}/reports", Volume("/inga-ui/inga-report")))
                        .withPortBindings(PortBinding(Ports.Binding.bindPort(4173), exposedPort))
                )
                .withEntrypoint("bash")
                .withCmd("-c", "npm run build && npm run preview")
                .withExposedPorts(exposedPort)
                .exec()
                .id
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