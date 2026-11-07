package org.example.project.features.audit.mapper

import org.example.project.core.domain.dto.audit.TransactionAuditDto
import org.example.project.features.audit.domain.models.TransactionAuditLog
import org.example.project.utils.formatter.toDecimalString

/**
 * Map TransactionAuditLog entity to DTO.
 */
fun TransactionAuditLog.toDto(): TransactionAuditDto.TransactionAuditLogDto =
    TransactionAuditDto.TransactionAuditLogDto(
        id = this.id.value,
        transactionId = this.transactionId,
        employeeId = this.employeeId,
        employeeNumber = this.employeeNumber,
        authorizationType = this.authorizationType,
        authorizingManagerId = this.authorizingManagerId,
        authorizingManagerName = this.authorizingManagerName,
        operation = this.operation,
        entityType = this.entityType,
        entityId = this.entityId,
        entityName = this.entityName,
        beforeState = this.beforeState,
        afterState = this.afterState,
        changesSummary = this.changesSummary,
        deviceId = this.deviceId,
        ipAddress = this.ipAddress,
        reason = this.reason,
        amountImpact = this.amountImpact?.toDecimalString(),
        currencyCode = this.currencyCode,
        metadata = this.metadata,
        timestamp = this.timestamp,
        fiscalRecordId = this.fiscalRecordId,
        traceId = this.traceId,
    )
