package inga.intellijinga

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowserBuilder

class IngaViewer : ToolWindowFactory {
    override fun createToolWindowContent(p: Project, window: ToolWindow) {
        if (!JBCefApp.isSupported()) {
            return
        }

        val webView = JBCefBrowserBuilder()
            .setUrl("http://localhost:4173/")
            .setEnableOpenDevToolsMenuItem(true)
            .build()
        window.component.add(webView.component)
    }
}