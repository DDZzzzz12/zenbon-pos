package org.example.project.core.presentation.strings.keys

import org.example.project.core.presentation.strings.StringKey

object TableDeleteKeys {
    val confirm = StringKey("table_delete.confirm")

    // getCancel delegates to CommonStrings — skipped
    val deleteTableTitle = StringKey("table_delete.delete_table_title") // {0} = tableNumber
    val deleteWarningBody = StringKey("table_delete.delete_warning_body")
}
