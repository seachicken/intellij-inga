package inga.intellijinga

import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.services.LanguageServer

interface IngaLanguageServerApi : LanguageServer {
    @JsonNotification("inga/diffChanged")
    fun diffChanged(param: DiffChanged)
}

data class DiffChanged(val diff: String, val uri: String? = null)