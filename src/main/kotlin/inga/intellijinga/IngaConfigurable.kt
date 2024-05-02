package inga.intellijinga

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField

class IngaConfigurable(private val p: Project) : Configurable {
    private val baseBranchField = JTextField()
    private val includePathPatternField = JTextField()
    private val excludePathPatternField = JTextField()

    override fun createComponent(): JComponent {
        val state = p.service<IngaSettings>().state
        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Base branch:",
                baseBranchField.apply { text = state.baseBranch })
            .addLabeledComponent("Include path pattern:",
                includePathPatternField.apply { text = state.includePathPattern })
            .addLabeledComponent("Exclude path pattern:",
                excludePathPatternField.apply { text = state.excludePathPattern })
            .panel
        return JPanel(BorderLayout()).apply {
            add(panel, BorderLayout.NORTH)
        }
    }

    override fun isModified(): Boolean {
        val state = p.service<IngaSettings>().state
        return state.baseBranch != baseBranchField.text
                || state.includePathPattern != includePathPatternField.text
                || state.excludePathPattern != excludePathPatternField.text
    }

    override fun apply() {
        p.service<IngaSettings>().loadState(
            IngaSettingsState(
                baseBranchField.text,
                includePathPatternField.text,
                excludePathPatternField.text
            )
        )
    }

    override fun getDisplayName(): String {
        return "Inga"
    }
}