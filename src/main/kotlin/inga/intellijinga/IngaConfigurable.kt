package inga.intellijinga

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.JTextField

class IngaConfigurable(private val p: Project) : Configurable {
    private val baseBranchField = JTextField()
    private val includePathPatternField = JTextField()
    private val excludePathPatternField = JTextField()
    private val portField = JTextField()

    override fun createComponent(): JComponent {
        val state = p.service<IngaSettings>().state
        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Base branch:",
                baseBranchField.apply { text = state.ingaUserParameters.baseBranch })
            .addLabeledComponent("Include path pattern:",
                includePathPatternField.apply { text = state.ingaUserParameters.includePathPattern })
            .addLabeledComponent("Exclude path pattern:",
                excludePathPatternField.apply { text = state.ingaUserParameters.excludePathPattern })
            .addLabeledComponent("inga-ui server port:",
                portField.apply { text = state.ingaUiUserParameters.port.toString() })
            .addComponent(JPanel().apply {
                border = BorderFactory.createTitledBorder("Binding containers")
                add(
                    FormBuilder.createFormBuilder()
                        .addLabeledComponent("inga container id:",
                            JTextField(state.ingaContainerId).apply { isEnabled = false })
                        .addLabeledComponent("inga-ui container id:",
                            JTextField(state.ingaUiContainerId).apply { isEnabled = false })
                        .panel
                )
            })
            .panel
        return JPanel(BorderLayout()).apply {
            add(panel, BorderLayout.NORTH)
        }
    }

    override fun isModified(): Boolean {
        val state = p.service<IngaSettings>().state
        return state.ingaUserParameters.baseBranch != baseBranchField.text
                || state.ingaUserParameters.includePathPattern != includePathPatternField.text
                || state.ingaUserParameters.excludePathPattern != excludePathPatternField.text
                || state.ingaUiUserParameters.port != portField.text.toInt()
    }

    override fun apply() {
        p.service<IngaSettings>().loadState(
            p.service<IngaSettings>().state.apply {
                ingaUserParameters = IngaContainerParameters(
                    baseBranchField.text,
                    includePathPatternField.text,
                    excludePathPatternField.text,
                )
                ingaUiUserParameters = IngaUiContainerParameters(
                    portField.text.toInt()
                )
            }
        )
    }

    override fun getDisplayName(): String {
        return "Inga"
    }
}