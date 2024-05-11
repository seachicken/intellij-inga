package inga.intellijinga

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowserBuilder
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import kotlin.time.Duration.Companion.seconds

class IngaViewer : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, window: ToolWindow) {
        if (!JBCefApp.isSupported()) {
            return
        }

        val port = project.service<IngaSettings>().state.port
        val webView = JBCefBrowserBuilder()
            .setUrl("http://localhost:$port/")
            .setEnableOpenDevToolsMenuItem(true)
            .build().apply {
                jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
                    override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                        super.onLoadEnd(browser, frame, httpStatusCode)
                        if (httpStatusCode != 200) {
                            GlobalScope.launch {
                                delay(10.seconds)
                                browser?.reload()
                            }
                        }
                    }
                }, cefBrowser)
            }
        window.component.add(webView.component)
    }
}