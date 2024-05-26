package inga.intellijinga

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import javax.swing.JComponent
import javax.swing.JTextField
import javax.swing.table.DefaultTableModel

class IngaConfigurable(private val project: Project) : Configurable {
    private val baseBranchField = JTextField()
    private val includePathPatternField = JTextField()
    private val excludePathPatternField = JTextField()
    private val portField = JTextField()
    private val bindMounts = DefaultTableModel()

    override fun createComponent(): JComponent {
        val state = project.service<IngaSettings>().state
        bindMounts.apply {
            addColumn("Source")
            addColumn("Destination")
            for (mount in state.ingaUserParameters.additionalMounts.entries) {
                addRow(arrayOf(mount.key, mount.value))
            }
        }
        return panel {
            row("Base branch:") {
                cell(baseBranchField).bindText(state.ingaUserParameters::baseBranch)
            }
            row("Include path pattern:") {
                cell(includePathPatternField).bindText(state.ingaUserParameters::includePathPattern)
            }
            row("Exclude path pattern:") {
                cell(excludePathPatternField).bindText(state.ingaUserParameters::excludePathPattern)
                    .comment("Filenames of glob pattern matching to exclude from analysis. (e.g., \"src/test/**\")")
            }
            group("Additional mounts") {
                row {
                    cell(mountsTable(bindMounts)).align(AlignX.FILL)
                }
            }
            row("Server port:") {
                cell(portField).bindIntText(state.ingaUiUserParameters::port)
            }
        }
    }

    private fun mountsTable(tableModel : DefaultTableModel) : JComponent {
        val table = JBTable(tableModel)
        return ToolbarDecorator.createDecorator(table).apply {
            setAddAction {
                tableModel.addRow(arrayOf("", ""))
                tableModel.fireTableDataChanged()
            }
            setRemoveAction {
                tableModel.removeRow(table.selectedRow)
                tableModel.fireTableDataChanged()
            }
        }.createPanel()
    }

    override fun isModified(): Boolean {
        val state = project.service<IngaSettings>().state
        val hasChangedMounts =
            state.ingaUserParameters.additionalMounts.entries.size != bindMounts.dataVector.size
                    || state.ingaUserParameters.additionalMounts.entries
                .filterIndexed { i, m ->
                    m.key == bindMounts.dataVector[i][0] && m.value == bindMounts.dataVector[i][1]
                }.size != state.ingaUserParameters.additionalMounts.size
        return state.ingaUserParameters.baseBranch != baseBranchField.text
                || state.ingaUserParameters.includePathPattern != includePathPatternField.text
                || state.ingaUserParameters.excludePathPattern != excludePathPatternField.text
                || hasChangedMounts
                || state.ingaUiUserParameters.port != portField.text.toInt()
    }

    override fun apply() {
        project.service<IngaSettings>().loadState(
            project.service<IngaSettings>().state.apply {
                ingaUserParameters = IngaContainerParameters(
                    baseBranchField.text,
                    includePathPatternField.text,
                    excludePathPatternField.text,
                    bindMounts.dataVector.associate { it[0] as String to it[1] as String }.toMutableMap()
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