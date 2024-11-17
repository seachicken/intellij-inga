package inga.intellijinga

import com.esotericsoftware.kryo.kryo5.minlog.Log
import com.intellij.ide.plugins.PluginManager
import com.intellij.ide.plugins.PluginStateManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class IngaStartupActivity(
    private val cs: CoroutineScope
) : ProjectActivity {
    override suspend fun execute(project: Project) {
        Log.info("INGA startup")
//        PluginStateManager.addStateListener {
//            Log.info("INGA plugin state changed: $it")
//            if (it.pluginId.idString == "inga.intellij-inga") {
//                it.descriptorPath
                cs.launch {
                    project.service<IngaService>().pullNewIngaImage()
                }
                cs.launch {
                    project.service<IngaService>().pullNewIngaUiImage()
                }
//            }
//        }
    }
}
