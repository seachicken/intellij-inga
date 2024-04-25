package inga.intellijinga.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import inga.intellijinga.IngaService

class StopAnalysisAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.service<IngaService>()?.stop()
    }
}