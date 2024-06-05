package inga.intellijinga

import com.intellij.openapi.components.*

@Service
@State(name = "inga-project", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class IngaSettings : PersistentStateComponent<IngaSettingsState> {
    var serverPort: Int? = null

    private var settings = IngaSettingsState()

    override fun getState(): IngaSettingsState {
        return settings
    }

    override fun loadState(settings: IngaSettingsState) {
        this.settings = settings
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
