package inga.intellijinga

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowserBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import kotlin.time.Duration.Companion.seconds

class IngaViewer(
    private val cs: CoroutineScope
) : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        if (!JBCefApp.isSupported()) {
            return
        }

        val browser = JBCefBrowserBuilder()
            .setEnableOpenDevToolsMenuItem(true)
            .build().apply {
                jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
                    override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                        super.onLoadEnd(browser, frame, httpStatusCode)
                        if (httpStatusCode != 200) {
                            cs.launch {
                                delay(10.seconds)
                                browser?.reload()
                            }
                        }
                    }
                }, cefBrowser)
            }
        toolWindow.component.add(browser.component)

        cs.launch {
            var prevServerPort: Int? = null
            var prevWebSocketPort: Int? = null
            while (isActive) {
                val serverPort = project.service<IngaSettings>().serverPort
                val webSocketPort = project.service<IngaSettings>().webSocketPort
                if (serverPort != prevServerPort || webSocketPort != prevWebSocketPort) {
                    prevServerPort = serverPort
                    prevWebSocketPort = webSocketPort
                    browser.loadURL("http://localhost:$serverPort/?wsPort=${webSocketPort}")
                }
                delay(10.seconds)
            }
        }
    }
}