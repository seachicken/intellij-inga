package inga.intellijinga

import com.esotericsoftware.kryo.kryo5.minlog.Log
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback.Adapter
import com.github.dockerjava.api.command.PullImageResultCallback
import com.github.dockerjava.api.command.WaitContainerResultCallback
import com.github.dockerjava.api.exception.DockerException
import com.github.dockerjava.api.exception.NotModifiedException
import com.github.dockerjava.api.model.*
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task.Backgroundable
import com.intellij.openapi.project.Project
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.LanguageServerWrapper
import com.redhat.devtools.lsp4ij.ServerStatus
import com.redhat.devtools.lsp4ij.lifecycle.LanguageServerLifecycleListener
import com.redhat.devtools.lsp4ij.lifecycle.LanguageServerLifecycleManager
import kotlinx.coroutines.*
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.messages.Message
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Paths
import java.util.regex.Pattern
import kotlin.io.path.pathString

@Service(Service.Level.PROJECT)
class IngaService(
    private val project: Project,
    private val cs: CoroutineScope
) {
    companion object {
        const val INGA_IMAGE_NAME = "ghcr.io/seachicken/inga"
        const val INGA_IMAGE_TAG = "0.28.0-java"
        const val INGA_UI_IMAGE_NAME = "ghcr.io/seachicken/inga-ui"
        const val INGA_UI_IMAGE_TAG = "0.10.6"
        const val INGA_SYNC_IMAGE_NAME = "ghcr.io/seachicken/inga-sync"
        const val INGA_SYNC_IMAGE_TAG = "0.1.0"
        const val INGA_SHARED_VOLUME_NAME = "inga"
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
            client.createVolumeCmd().withName(INGA_SHARED_VOLUME_NAME).exec()
            client.createVolumeCmd().withName(ingaContainerName).exec()
            val startIngaUi = cs.launch {
                val unusedPort = ServerSocket(0).use {
                    it.localPort
                }
                project.service<IngaSettings>().webSocketPort = unusedPort
                webSocketServer = IngaWebSocketServer(unusedPort, project)
                webSocketServer?.start()

                setUpIngaUiContainer().also {
                    client.startContainerCmd(it).exec()
                }
            }
            val syncVolume = cs.launch {
                syncToSharedVolume()
            }
            val setUpInga = cs.async {
                setUpIngaContainer(project.service<IngaSettings>().state)
            }
            joinAll(startIngaUi, syncVolume)
            setUpInga.await().also {
                client.startContainerCmd(it).exec()
            }
        }
    }

    fun stop() {
        Log.info("INGA stop Inga analysis")
        if (!::client.isInitialized) {
            throw IllegalStateException("Inga analysis is not running")
        }

        runBlocking {
            joinAll(
                cs.launch {
                    webSocketServer?.stop()
                    stopContainer(ingaUiContainerName)
                },
                cs.launch {
                    stopContainer(ingaContainerName)
                }
            )
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

    private fun syncToSharedVolume() {
        val gradleHome = Paths.get(System.getProperty("user.home")).resolve(".gradle")
        if (!Files.exists(gradleHome)) {
            return
        }

        var hasSynched = false
        var detailMessage = ""
        var percentage = 0.0
        val percentagePattern = Pattern.compile("(\\d+)%")
        ProgressManager.getInstance().run(object : Backgroundable(project, "Inga: Sync to Docker volumes", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Copying gradle caches..."
                while (!hasSynched) {
                    indicator.text2 = detailMessage
                    indicator.fraction = percentage / 100
                    Thread.sleep(200)
                }
            }
        })
        client
            .pullImageCmd(INGA_SYNC_IMAGE_NAME)
            .withTag(INGA_SYNC_IMAGE_TAG)
            .exec(PullImageResultCallback())
            .awaitCompletion()

        val binds = listOf(
            Bind(INGA_SHARED_VOLUME_NAME, Volume("/inga-shared"), AccessMode.rw),
            Bind(gradleHome.pathString, Volume("/root/.gradle-host"), AccessMode.ro)
        )
        client
            .createContainerCmd("$INGA_SYNC_IMAGE_NAME:$INGA_SYNC_IMAGE_TAG")
            .withHostConfig(
                HostConfig.newHostConfig()
                    .withBinds(binds)
                    .withAutoRemove(true)
            )
            .withCmd(
                "sh", "-c",
                // Sharing the host's Gradle dependency cache directly with the container can cause conflicts and errors.
                // Instead, refer to copied caches.
                // https://docs.gradle.org/current/userguide/dependency_caching.html#sec:shared-readonly-cache
                "mkdir -p /inga-shared/.gradle/caches && " +
                        "rsync -rav --info=progress2 --no-inc-recursive --exclude='*.lock' --exclude='gc.properties' " +
                        "/root/.gradle-host/caches/ /inga-shared/.gradle/caches/"
            )
            .exec()
            .also {
                client.startContainerCmd(it.id).exec()
                client.logContainerCmd(it.id)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withFollowStream(true)
                    .exec(object : Adapter<Frame>() {
                        override fun onNext(item: Frame?) {
                            super.onNext(item)
                            item?.let {
                                detailMessage = String(item.payload)
                                val matcher = percentagePattern.matcher(detailMessage);
                                if (matcher.find()) {
                                    percentage = matcher.group(1).toDouble()
                                }
                            }
                        }
                    })
                client.waitContainerCmd(it.id)
                    .exec(WaitContainerResultCallback())
                    .awaitCompletion()
            }
        hasSynched = true
    }

    private fun setUpIngaContainer(state: IngaSettingsState): String {
        var ingaContainer = client
            .listContainersCmd()
            .withShowAll(true)
            .exec()
            .find(isTargetContainer(ingaContainerName))

        fun pullNewImage() {
            var hasPulled = false
            var detailMessage = ""
            ProgressManager.getInstance().run(object : Backgroundable(project, "Inga: Pull inga image", false) {
                override fun run(indicator: ProgressIndicator) {
                    while (!hasPulled) {
                        indicator.text2 = detailMessage
                        Thread.sleep(200)
                    }
                }
            })
            client
                .pullImageCmd(INGA_IMAGE_NAME)
                .withTag(INGA_IMAGE_TAG)
                .exec(object : PullImageResultCallback() {
                    override fun onNext(item: PullResponseItem?) {
                        super.onNext(item)
                        item?.status?.let { detailMessage = it }
                    }
                }).awaitCompletion()
            hasPulled = true
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
        }
    }

    private fun createIngaContainer(state: IngaSettingsState): String {
        val command = mutableListOf(
            "--mode", "server", "--root-path", "/work", "--output-path", "/inga-output", "--temp-path", "/inga-output",
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
            // A lock file needs to be written to /work/.gradle when checking dependencies.
            // https://github.com/seachicken/jvm-dependency-loader/blob/1648be5b4c9e0a70e1fb4680895df2751aa16e9f/src/main/java/inga/jvmdependencyloader/buildtool/Gradle.java#L50
            Bind(project.basePath, Volume("/work"), AccessMode.rw),
            Bind(INGA_SHARED_VOLUME_NAME, Volume("/inga-shared"), AccessMode.ro),
            Bind(ingaContainerName, Volume("/inga-output"), AccessMode.rw)
        )
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
            .withHostConfig(
                HostConfig.newHostConfig()
                    .withBinds(binds)
            )
            .withEnv("GRADLE_RO_DEP_CACHE=/inga-shared/.gradle/caches")
            .withWorkingDir("/work")
            .withCmd(command)
            .exec()
            .id.also {
                state.ingaContainerParameters = state.ingaUserParameters
            }
    }

    private fun setUpIngaUiContainer(): String {
        var ingaUiContainer = client
            .listContainersCmd()
            .withShowAll(true)
            .exec()
            .find(isTargetContainer(ingaUiContainerName))

        fun pullNewImage() {
            client
                .pullImageCmd(INGA_UI_IMAGE_NAME)
                .withTag(INGA_UI_IMAGE_TAG)
                .exec(PullImageResultCallback())
                .awaitCompletion()
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
        }
    }

    private fun stopContainer(containerName: String) {
        client
            .listContainersCmd()
            .exec()
            .find(isTargetContainer(containerName))
            ?.let {
                try {
                    client.stopContainerCmd(it.id).exec()
                } catch (e: NotModifiedException) {
                    Log.info("INGA already requested to stop container", e)
                }
            }
    }

    private fun removeImageIfUnused(imageName: String) {
        val usedContainers = client
            .listContainersCmd()
            .withShowAll(true)
            .exec()
            .filter { it.image.contains(imageName) }
        if (usedContainers.isEmpty()) {
            client.listImagesCmd()
                .exec()
                .find { it.repoTags.contains(imageName) }
                ?.let {
                    client.removeImageCmd(imageName).exec()
                }
        }
    }

    private fun isTargetContainer(name: String): (Container) -> Boolean =
        { it.names[0].substringAfter("/") == name }
}