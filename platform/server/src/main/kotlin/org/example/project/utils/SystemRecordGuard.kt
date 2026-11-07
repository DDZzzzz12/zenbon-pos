package org.example.project.utils

import io.ktor.http.HttpStatusCode
import org.example.project.core.error.PosErrorCode
import org.example.project.infrastructure.errors.PosException

/**
 * The refusal every service throws when an operator-facing update or delete targets a record the
 * system depends on (`SystemRecords`). 409, [PosErrorCode.SYSTEM_RECORD_PROTECTED].
 */
object SystemRecordGuard {
    fun protectedRecord(
        kind: String,
        label: String,
        action: String,
    ): PosException =
        PosException(
            PosErrorCode.SYSTEM_RECORD_PROTECTED,
            HttpStatusCode.Conflict,
            "$kind '$label' is a system record and cannot be $action",
        )
}
