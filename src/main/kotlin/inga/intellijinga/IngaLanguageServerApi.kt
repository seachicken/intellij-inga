package inga.intellijinga

import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.CompletableFuture

interface IngaLanguageServerApi : LanguageServer {
    @JsonRequest("inga/getModulePaths")
    fun getModulePaths(): CompletableFuture<GetModulePathsResponse>
    @JsonRequest("inga/getConfig")
    fun getConfig(): CompletableFuture<IngaConfig>
    @JsonRequest("inga/updateConfig")
    fun updateConfig(config: IngaConfig?): CompletableFuture<IngaConfig>

    @JsonNotification("inga/diffChanged")
    fun diffChanged(param: DiffChanged)
}

data class GetModulePathsResponse(val modulePaths: List<String>)
data class DiffChanged(val diff: String, val uri: String? = null)