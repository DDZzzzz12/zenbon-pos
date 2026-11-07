package org.example.project.features.customexport

import org.example.project.features.customexport.domain.catalogue.ExportAggregateField
import org.example.project.features.customexport.domain.catalogue.ExportCatalogue
import org.example.project.features.customexport.domain.catalogue.ExportField
import org.example.project.testutils.Snapshots
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Pins the whole custom-export catalogue: every subject's offerings (key, heading, label, type,
 * capability), the joins behind every relation path, the aggregates a subject withholds and the
 * capabilities each offering costs.
 *
 * The catalogue is derived from the foreign-key graph, so it moves whenever a foreign key stops being
 * visible to the walk — for example when a cross-area foreign key leaves an Exposed table object and is
 * declared by the host instead. A stored export definition names its fields by these keys, so a key that
 * disappears breaks a saved export silently.
 */
@DisplayName("Custom-export catalogue pinned whole")
class ExportCatalogueSnapshotTest {
    @Test
    fun `the catalogue is unchanged`() {
        Snapshots.assertMatches("custom-export-catalogue.snapshot.txt", catalogueLines())
    }

    private fun catalogueLines(): List<String> =
        ExportCatalogue.subjects.flatMap { subject ->
            val offerings =
                subject.allOfferings.map { offering ->
                    val kind =
                        when (offering) {
                            is ExportField -> "field column=${offering.column.table.tableName}.${offering.column.name}"
                            is ExportAggregateField -> "aggregate table=${offering.table.tableName} period=${offering.periodScoped}"
                        }
                    "${subject.key} offering ${offering.key} | group=${offering.group} | label=${offering.label} | " +
                        "type=${offering.type} | capability=${offering.capability} | $kind"
                }
            val paths =
                (subject.paths + subject.aggregatePaths).distinct().map { path ->
                    "${subject.key} path ${path.key} | label=${path.label} | " +
                        path.hops.joinToString(" ; ") { hop ->
                            "${hop.target.tableName} on ${hop.from.table.tableName}.${hop.from.name}=${hop.to.table.tableName}.${hop.to.name}"
                        } + " | cardinality=${path.cardinality}"
                }
            val suppressed = subject.suppressedAggregates.map { "${subject.key} suppressed ${it.key}" }
            val requirements =
                subject.capabilityRequirements().map { (key, caps) ->
                    "${subject.key} requires $key = ${caps.map { it.name }.sorted()}"
                }
            listOf("${subject.key} subject root=${subject.root.tableName}") + offerings + paths + suppressed + requirements
        }
}
