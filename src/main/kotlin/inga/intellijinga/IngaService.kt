package inga.intellijinga

import com.esotericsoftware.kryo.kryo5.minlog.Log
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.PullImageResultCallback
import com.github.dockerjava.api.model.PullResponseItem
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import com.intellij.openapi.components.Service
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Service(Service.Level.PROJECT)
class IngaService(
    private val cs: CoroutineScope
) {
    companion object {
        const val IMAGE_NAME = "ghcr.io/seachicken/inga"
        const val IMAGE_TAG = "latest-java"
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

        cs.launch {
            val ingaContainer = client
                .listContainersCmd()
                .withShowAll(true)
                .exec()
                .find { it.image == "$IMAGE_NAME:$IMAGE_TAG" }

            val containerId = if (ingaContainer == null) {
                client
                    .pullImageCmd(IMAGE_NAME)
                    .withTag(IMAGE_TAG)
                    .exec(object : PullImageResultCallback() {
                        override fun onNext(item: PullResponseItem?) {
                            Log.info(item?.status)
                            super.onNext(item)
                        }
                    }).awaitCompletion()
                client
                    .createContainerCmd("$IMAGE_NAME:$IMAGE_TAG")
                    .exec()
                    .id
            } else {
                ingaContainer.id
            }

            client.startContainerCmd(containerId).exec()
        }
    }

    fun stop() {
        Log.info("stop Inga analysis")
        if (!::client.isInitialized) {
            throw IllegalStateException("Inga analysis is not running")
        }
    }
}