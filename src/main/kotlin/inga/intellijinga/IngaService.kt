package inga.intellijinga

import com.esotericsoftware.kryo.kryo5.minlog.Log
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
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
//        const val IMAGE_TAG = "latest-java"
        const val INGA_IMAGE_TAG = "0.12.0-pre22-java"
        const val INGA_UI_IMAGE_NAME = "ghcr.io/seachicken/inga-ui"
        const val INGA_UI_IMAGE_TAG = "0.1.4"
    }

    var ingaContainerId: String = ""

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
                ingaContainerId = startIngaContainer()
            }
            launch {
                startIngaUiContainer()
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
                stopIngaContainer()
            }
            launch {
                stopIngaUiContainer()
            }
        }
    }

    private fun startIngaContainer(): String {
        val ingaContainer = client
            .listContainersCmd()
            .withShowAll(true)
            .exec()
            .find { it.image == "$INGA_IMAGE_NAME:$INGA_IMAGE_TAG" }

        val containerId = if (ingaContainer == null) {
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
            if (ingaContainer.mounts.any { it.source?.endsWith(project.basePath ?: "") == false }) {
                client.removeContainerCmd(ingaContainer.id).exec()
                createIngaContainer()
            } else {
                ingaContainer.id
            }
        }

        client.startContainerCmd(containerId).exec()

        return containerId
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

    private fun startIngaUiContainer(): String {
        val ingaUiContainer = client
            .listContainersCmd()
            .withShowAll(true)
            .exec()
            .find { it.image == "$INGA_UI_IMAGE_NAME:$INGA_UI_IMAGE_TAG" }

        val containerId = if (ingaUiContainer == null) {
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
                .withStdinOpen(true)
                .withHostConfig(
                    HostConfig.newHostConfig()
                        .withBinds(Bind(project.basePath, Volume("/work")))
                        .withPortBindings(PortBinding(Ports.Binding.bindPort(4173), exposedPort))
                )
                .withCmd("html-report", "//")
                .withExposedPorts(exposedPort)
                .exec()
                .id
        } else {
            ingaUiContainer.id
        }

        client.startContainerCmd(containerId).exec()
        client.execCreateCmd(containerId)
            .withCmd("bash", "-c", "npm run build --ingapath=../work/reports/report.json && npm run preview")
            .withAttachStdout(true)
            .withAttachStderr(true)
            .exec()
            .also {
                client.execStartCmd(it.id)
                    .exec(object : ResultCallback.Adapter<Frame>() {
                        override fun onNext(item: Frame?) {
                            Log.info(item.toString())
                        }
                    }).awaitCompletion()
            }

        return containerId
    }

    private fun stopIngaContainer() {
        val ingaContainer = client
            .listContainersCmd()
            .withShowAll(true)
            .exec()
            .find { it.image == "$INGA_IMAGE_NAME:$INGA_IMAGE_TAG" }
        if (ingaContainer != null) {
            client.stopContainerCmd(ingaContainer.id).exec()
        }
    }

    private fun stopIngaUiContainer() {
        val ingaContainer = client
            .listContainersCmd()
            .withShowAll(true)
            .exec()
            .find { it.image == "$INGA_UI_IMAGE_NAME:$INGA_UI_IMAGE_TAG" }
        if (ingaContainer != null) {
            client.stopContainerCmd(ingaContainer.id).exec()
        }
    }
}