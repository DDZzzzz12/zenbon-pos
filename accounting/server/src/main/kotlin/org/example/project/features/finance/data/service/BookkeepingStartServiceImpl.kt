package org.example.project.features.finance.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import org.example.project.core.domain.dto.finance.BookkeepingStartDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.finance.domain.service.BookkeepingStartService
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.currentTimeStamp

/**
 * See [BookkeepingStartService].
 *
 * The system is single-shop today, so the shop is resolved here rather than taken from the path —
 * matching every other endpoint in this feature, none of which makes the client carry a shopId.
 *
 * It lives in `pos-server` rather than `accounting-document` (where the rest of this slice sits)
 * because [ConfigurationRepository] is a `pos-server` class; `accounting-document` cannot see it. The
 * interface is in `core-data`, so the routes and the two list services depend on that alone.
 *
 * [ConfigurationRepository]'s methods are `suspend` and expect an **ambient transaction**, hence the
 * `dbQuery` wrappers. [cutoff] therefore opens its own transaction — call it *before* entering a
 * caller's own `dbQuery`, never inside one.
 *
 * [cutoff] creates the default just as [get] does, through the one [startDateCreatingDefault]
 * helper. It used to be a pure read returning null — "filter nothing" — while only [get], reachable
 * solely from the Zuordnung screen, created the value. An operator who opened *Rechnungen* first
 * therefore saw the shop's whole invoice history reported as unpaid.
 */
class BookkeepingStartServiceImpl(
    private val configRepository: ConfigurationRepository,
) : BookkeepingStartService {
    override suspend fun get(): BookkeepingStartDto.ResponseDto =
        dbQuery {
            val shopId = requireShopId()
            BookkeepingStartDto.ResponseDto(
                startDate = startDateCreatingDefault(shopId),
                needsAcknowledgement = !isAcknowledged(shopId),
            )
        }

    override suspend fun update(startDate: String): BookkeepingStartDto.ResponseDto =
        dbQuery {
            val parsed = parseOrReject(startDate)
            val shopId = requireShopId()
            configRepository.setSetting(shopId, ConfigKey.BOOKKEEPING_START, parsed.toString())
            // Choosing a date means the operator has seen it; there is nothing left to announce.
            markAcknowledged(shopId)
            BookkeepingStartDto.ResponseDto(startDate = parsed.toString(), needsAcknowledgement = false)
        }

    override suspend fun acknowledge(): BookkeepingStartDto.ResponseDto =
        dbQuery {
            val shopId = requireShopId()
            val startDate = startDateCreatingDefault(shopId)
            markAcknowledged(shopId)
            BookkeepingStartDto.ResponseDto(startDate = startDate, needsAcknowledgement = false)
        }

    override suspend fun cutoff(): LocalDateTime? =
        dbQuery {
            val shopId = shopIdOrNull() ?: return@dbQuery null
            LocalDateTime(LocalDate.parse(startDateCreatingDefault(shopId)), LocalTime(0, 0))
        }

    /**
     * The stored Buchungsbeginn, writing the first day of the current month if there is none.
     *
     * The **single** place that decides what the default is, so no caller can establish a different
     * one. The write is deliberate: the alternative is a date constant baked into
     * `ConfigKey.defaultValue`, which is stale before the release ships.
     */
    private suspend fun startDateCreatingDefault(shopId: Long): String {
        val stored = configRepository.getSettingAsString(shopId, ConfigKey.BOOKKEEPING_START.key)
        if (!stored.isNullOrBlank()) return stored

        val today = currentTimeStamp().date
        val default = LocalDate(today.year, today.month, 1).toString()
        configRepository.setSetting(shopId, ConfigKey.BOOKKEEPING_START, default)
        return default
    }

    private suspend fun isAcknowledged(shopId: Long): Boolean =
        configRepository
            .getSettingAsString(shopId, ConfigKey.BOOKKEEPING_START_ACKNOWLEDGED.key)
            ?.trim()
            .equals("true", ignoreCase = true)

    private suspend fun markAcknowledged(shopId: Long) {
        configRepository.setSetting(shopId, ConfigKey.BOOKKEEPING_START_ACKNOWLEDGED, true)
    }

    /**
     * A malformed date must never reach the column: it would parse to nothing downstream and
     * silently empty both work lists, which reads exactly like "everything is done".
     */
    private fun parseOrReject(raw: String): LocalDate =
        runCatching { LocalDate.parse(raw.trim()) }.getOrNull()
            ?: throw PosException(
                PosErrorCode.INVALID_REQUEST,
                HttpStatusCode.BadRequest,
                "Buchungsbeginn must be an ISO date (yyyy-MM-dd), was '$raw'",
            )

    /** The single shop, or null on a system that has not been set up yet. */
    private fun shopIdOrNull(): Long? =
        ShopInfo
            .all()
            .firstOrNull()
            ?.id
            ?.value

    private fun requireShopId(): Long =
        shopIdOrNull()
            ?: throw PosException(
                PosErrorCode.SHOP_NOT_FOUND,
                HttpStatusCode.NotFound,
                "No shop configured",
            )
}
