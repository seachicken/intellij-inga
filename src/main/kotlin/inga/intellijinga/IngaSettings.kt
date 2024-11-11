package inga.intellijinga

import com.intellij.openapi.components.*

@Service
@State(name = "inga-project", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class IngaSettings : PersistentStateComponent<IngaSettingsState> {
    var serverPort: Int? = null
    var webSocketPort: Int? = null
    var modulePaths: List<String> = emptyList()
    var config: IngaConfig? = null

    private var settings = IngaSettingsState()

    override fun getState(): IngaSettingsState {
        return settings
    }

    override fun loadState(settings: IngaSettingsState) {
        this.settings = settings
    }

    fun getCallerHint(path: String): List<Client>? {
        val modulePath = getModulePath(path)
        return config?.servers?.find { it.path == modulePath }?.clients
    }

    fun applyRequest(addRequest: AddConnectionPathsRequest): IngaSettings {
        config?.apply {
            val modulePath = getModulePath(addRequest.serverPath)
            if (modulePath.isEmpty()) {
                return@apply
            }
            if (servers.none { it.path == modulePath } && addRequest.clientPaths != null) {
                servers += Server(modulePath, addRequest.clientPaths.map { p -> Client(p) })
            } else {
                servers = servers.mapNotNull {
                    if (it.path == modulePath) {
                        if (addRequest.clientPaths == null) {
                            null
                        } else {
                            it.apply {
                                clients = addRequest.clientPaths.map { p -> Client(p) }
                            }
                        }
                    } else {
                        it
                    }
                }
            }
        }
        return this
    }

    private fun getModulePath(path: String): String {
        return modulePaths.find { path.startsWith(it) } ?: ""
    }
}

data class IngaSettingsState(
    var ingaUserParameters: IngaContainerParameters = IngaContainerParameters(),
    var ingaContainerParameters: IngaContainerParameters? = null
)

data class IngaContainerParameters(
    var baseBranch: String = "",
    var includePathPattern: String = "",
    var excludePathPattern: String = "",
    var additionalMounts: MutableMap<String, String> = mutableMapOf()
)

data class IngaConfig(
    var servers: List<Server>
)

data class Server(
    var path: String,
    var clients: List<Client>
)

data class Client(
    var path: String
)
