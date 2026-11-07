/**
 * Implementation of [CashBookService].
 *
 * Throws [PosException] on errors; the global StatusPages handler converts to
 * application/problem+json. [create] never produces a fiscal book — [CashBookDto.CreateRequestDto]
 * deliberately has no `isFiscal` field, so `isFiscal = false` is hardcoded here. [update] can never
 * flip `isFiscal` either, for the same reason, and additionally refuses to deactivate a book that
 * would strand cash: the fiscal book can never be deactivated, and a non-fiscal book can only be
 * deactivated while its balance is zero.
 *
 * Every response carries the shop's ISO currency, resolved **once per request** outside `dbQuery`
 * from the same `IShopConfigurationManager.getDefaultCurrency()` the Kassenbuch summary uses, so a
 * book with no entries in the client's viewed period still renders its balance in the right unit.
 */
package org.example.project.features.kassenbuch.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.dto.kassenbuch.CashBookDto
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.error.PosErrorCode
import org.example.project.features.kassenbuch.domain.SigningTerminalEligibility
import org.example.project.features.kassenbuch.domain.models.CashBook
import org.example.project.features.kassenbuch.domain.repository.CashBookRepository
import org.example.project.features.kassenbuch.domain.repository.KassenbuchRepository
import org.example.project.features.kassenbuch.domain.service.CashBookService
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.setup.domain.repository.ShopInfoRepository
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.terminal.domain.repository.TerminalRepository
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.dbQuery
import org.example.project.utils.errorHandling.ExceptionConverter
import org.example.project.utils.formatter.toDecimalString
import org.example.project.utils.log.AuditEntityRef

class CashBookServiceImpl(
    private val cashBookRepository: CashBookRepository,
    private val kassenbuchRepository: KassenbuchRepository,
    private val shopInfoRepository: ShopInfoRepository,
    private val configManager: IShopConfigurationManager,
    private val terminalRepository: TerminalRepository,
) : CashBookService {
    override suspend fun list(includeInactive: Boolean): List<CashBookDto.ResponseDto> {
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            val shop = defaultShop()
            cashBookRepository.findByShop(shop, includeInactive).map { it.toResponseDto(currency) }
        }
    }

    override suspend fun create(request: CashBookDto.CreateRequestDto): CashBookDto.ResponseDto {
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            val shop = defaultShop()
            val name = request.name.trim()
            requireUniqueName(shop, name, exceptBookId = null)
            val book =
                cashBookRepository.create(
                    shop = shop,
                    name = name,
                    isFiscal = false,
                    datevAccount = request.datevAccount,
                    closeInterval = request.closeInterval,
                )
            book.kost1 = request.kost1
            book.kost2 = request.kost2
            book.toResponseDto(currency)
        }
    }

    override suspend fun update(
        bookId: Long,
        request: CashBookDto.UpdateRequestDto,
    ): CashBookDto.ResponseDto {
        val currency = configManager.getDefaultCurrency()
        return dbQuery {
            val book = cashBookRepository.findById(bookId) ?: throw bookNotFound(bookId)

            if (!request.isActive) {
                requireDeactivatable(book)
            }

            val name = request.name.trim()
            requireUniqueName(book.shop, name, exceptBookId = book.id.value)

            book.name = name
            book.datevAccount = request.datevAccount
            book.closeInterval = request.closeInterval
            book.kost1 = request.kost1
            book.kost2 = request.kost2
            book.isActive = request.isActive
            book.sortOrder = request.sortOrder

            book.toResponseDto(currency)
        }
    }

    /**
     * Refuses a name another book of [shop] already carries — compared trimmed and
     * case-insensitively, so "Tresor", "tresor" and " Tresor " are one name (409).
     *
     * Deactivated books count too. A deactivated book's name still labels its historic entries, its
     * closes and the exports that name the book, and re-activating it (which is never blocked) would
     * otherwise leave two identically named ledgers side by side — the operator could no longer tell
     * which one a Kassensturz or a Geldtransit targets. Re-activate the old book, or pick another
     * name. [exceptBookId] lets a book keep (or re-case) its own name on edit.
     */
    private fun requireUniqueName(
        shop: ShopInfo,
        name: String,
        exceptBookId: Long?,
    ) {
        val clash =
            cashBookRepository
                .findByShop(shop, includeInactive = true)
                .firstOrNull { it.id.value != exceptBookId && it.name.trim().equals(name, ignoreCase = true) }
                ?: return
        val state = if (clash.isActive) "" else ", deactivated"
        throw PosException(
            PosErrorCode.CASH_BOOK_NAME_ALREADY_TAKEN,
            HttpStatusCode.Conflict,
            "A cash book named '${clash.name}' already exists (id=${clash.id.value}$state)",
        )
    }

    /**
     * The fiscal book can never be deactivated, whatever its balance — a shop must always have
     * one. A non-fiscal book may only be deactivated while its balance is zero; deactivating it
     * otherwise would strand cash in a book no longer visible to daily operation.
     */
    private fun requireDeactivatable(book: CashBook) {
        if (book.isFiscal) {
            throw PosException(
                PosErrorCode.VALIDATION_FAILED,
                HttpStatusCode.UnprocessableEntity,
                "'${book.name}' is the fiscal Ladenkasse and can never be deactivated",
            )
        }

        val balance = kassenbuchRepository.getCurrentBalance(book.shop, book.id.value)
        if (balance.signum() != 0) {
            throw PosException(
                PosErrorCode.VALIDATION_FAILED,
                HttpStatusCode.UnprocessableEntity,
                "Cannot deactivate '${book.name}': its balance is ${balance.toDecimalString()}, not zero",
            )
        }
    }

    override suspend fun signingTerminalCandidates(bookId: Long): List<CashBookDto.SigningTerminalCandidateDto> {
        val mode = configManager.getTseMode()
        return dbQuery {
            val book = cashBookRepository.findById(bookId) ?: throw bookNotFound(bookId)
            if (!book.isFiscal) return@dbQuery emptyList()
            terminalRepository
                .getActive()
                .filter { SigningTerminalEligibility.isEligible(it, book.shop, mode) }
                .map { CashBookDto.SigningTerminalCandidateDto(terminalId = it.id.value, name = it.name ?: it.clientId, clientId = it.clientId) }
        }
    }

    override suspend fun setSigningTerminal(
        bookId: Long,
        terminalId: Long?,
        actorId: String,
        deviceId: String,
    ): CashBookDto.ResponseDto {
        val currency = configManager.getDefaultCurrency()
        val mode = configManager.getTseMode()
        return ExceptionConverter.catchingThrows(
            context = "setSigningTerminal(bookId=$bookId, terminalId=$terminalId)",
            employeeId = actorId,
            operation = "SET_CASH_BOOK_SIGNING_TERMINAL",
            entityType = "CASH_BOOK",
            deviceId = deviceId,
            auditEntity = { AuditEntityRef(id = it.id.toString(), name = it.name) },
            auditState = { "signingTerminalId=${it.signingTerminalId}" },
        ) {
            dbQuery {
                val book = cashBookRepository.findById(bookId) ?: throw bookNotFound(bookId)
                if (!book.isFiscal) throw ineligible("'${book.name}' is not the fiscal Ladenkasse")
                book.signingTerminal = terminalId?.let { requireEligibleTerminal(it, book.shop, mode) }
                book.toResponseDto(currency)
            }
        }
    }

    private fun requireEligibleTerminal(
        terminalId: Long,
        shop: ShopInfo,
        mode: TseMode,
    ): Terminal {
        val terminal = terminalRepository.findById(terminalId) ?: throw ineligible("terminal $terminalId does not exist")
        SigningTerminalEligibility.reasonIneligible(terminal, shop, mode)?.let { throw ineligible("terminal ${terminal.clientId}: $it") }
        return terminal
    }

    private fun ineligible(why: String) = PosException(PosErrorCode.CASH_BOOK_SIGNER_INELIGIBLE, HttpStatusCode.Conflict, why)

    private fun defaultShop() =
        shopInfoRepository.findById(1L)
            ?: throw PosException(
                PosErrorCode.RESOURCE_NOT_FOUND,
                HttpStatusCode.NotFound,
                "Default shop not found",
            )

    private fun CashBook.toResponseDto(currency: String): CashBookDto.ResponseDto =
        CashBookDto.ResponseDto(
            id = id.value,
            name = name,
            isFiscal = isFiscal,
            datevAccount = datevAccount,
            kost1 = kost1,
            kost2 = kost2,
            closeInterval = closeInterval,
            isActive = isActive,
            sortOrder = sortOrder,
            currentBalance = kassenbuchRepository.getCurrentBalance(shop, id.value).toDecimalString(),
            currency = currency,
            signingTerminalId = signingTerminal?.id?.value,
            signingTerminalName = signingTerminal?.let { it.name ?: it.clientId },
        )

    private fun bookNotFound(id: Long) = PosException(PosErrorCode.RESOURCE_NOT_FOUND, HttpStatusCode.NotFound, "Cash book with id=$id not found")
}
