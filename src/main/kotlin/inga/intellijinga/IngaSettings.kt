package inga.intellijinga

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service
@State(name = "inga-project", storages = [Storage("inga-project.xml")])
class IngaSettings : PersistentStateComponent<IngaSettingsState> {
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
    var ingaUiUserParameters: IngaUiContainerParameters = IngaUiContainerParameters(),
    var ingaContainerParameters: IngaContainerParameters? = null,
    var ingaUiContainerParameters: IngaUiContainerParameters? = null,
)

data class IngaContainerParameters(
    var baseBranch: String = "main",
    var includePathPattern: String = "",
    var excludePathPattern: String = "",
    var additionalMounts: MutableMap<String, String> = mutableMapOf()
)

data class IngaUiContainerParameters(
    var port: Int = 4173
)
