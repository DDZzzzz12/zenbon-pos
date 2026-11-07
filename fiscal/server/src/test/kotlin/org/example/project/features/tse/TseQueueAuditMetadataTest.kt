package org.example.project.features.tse

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

class TseQueueAuditMetadataTest {
    @Test
    fun `audit metadata with string values serializes`() {
        val metadata: Map<String, Any> =
            mapOf(
                "queueEntryId" to 42L.toString(),
                "retryCount" to 3.toString(),
                "processType" to "PROCESS_TYPE_OTHER",
            )

        @Suppress("UNCHECKED_CAST")
        val asStrings = metadata as Map<String, String>
        val out = Json.encodeToString(asStrings)
        assertTrue(out.contains("queueEntryId"))
    }
}
