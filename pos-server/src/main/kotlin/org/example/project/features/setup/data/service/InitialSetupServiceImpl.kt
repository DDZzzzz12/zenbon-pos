/**
 * Implementation of InitialSetupService.
 *
 * Throws [PosException] on errors; the global StatusPages handler converts to
 * application/problem+json. Migrated from Result<T, Error> in Stage B Batch 4.
 */
package org.example.project.features.setup.data.service

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import org.example.project.BuildConfig
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.core.domain.dto.employee.EmployeeInfoDto
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.domain.dto.setup.InitialSetupDto
import org.example.project.core.domain.dto.setup.SetupReadinessDto
import org.example.project.core.domain.dto.setup.ShopInfoDto
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.domain.enums.setup.SetupSteps
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.error.PosErrorCode
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.employee.domain.models.EmployeeInfo
import org.example.project.features.employee.domain.repository.EmployeeInfoRepository
import org.example.project.features.employee.domain.repository.EmployeeRepository
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.domain.repository.CashBookRepository
import org.example.project.features.payment.database.tables.PaymentTypeTable
import org.example.project.features.payment.database.tables.VatTable
import org.example.project.features.payment.domain.models.PaymentType
import org.example.project.features.payment.domain.models.Vat
import org.example.project.features.payment.domain.repository.PaymentTypeRepository
import org.example.project.features.product.database.tables.ProductCategoryTable
import org.example.project.features.product.database.tables.ProductExtrasTable
import org.example.project.features.product.database.tables.ProductTable
import org.example.project.features.product.domain.models.Product
import org.example.project.features.product.domain.models.ProductCategory
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.features.setup.database.tables.ShopInfoTable
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.setup.domain.repository.ShopInfoRepository
import org.example.project.features.setup.domain.repository.VatRepository
import org.example.project.features.setup.domain.service.InitialSetupService
import org.example.project.features.table.database.tables.RTableTable
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.infrastructure.errors.PosException
import org.example.project.utils.KasseSerialGenerator
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.hashPassword
import org.example.project.utils.formatter.toBigDecimal
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Concrete implementation of InitialSetupService interface.
 *
 * Provides complete system initialization functionality with transaction safety
 * and comprehensive error handling. All setup operations are atomic - either
 * all data is created successfully or no changes are made.
 */
class InitialSetupServiceImpl(
    private val employeeRepository: EmployeeRepository,
    private val employeeInfoRepository: EmployeeInfoRepository,
    private val shopInfoRepository: ShopInfoRepository,
    private val configurationRepository: ConfigurationRepository,
    private val paymentTypeRepository: PaymentTypeRepository,
    private val vatRepository: VatRepository,
    private val configManager: IShopConfigurationManager,
    private val cashBookRepository: CashBookRepository = CashBookRepositoryImpl(),
) : InitialSetupService {
    private val logger = LoggerFactory.getLogger(InitialSetupServiceImpl::class.java)

    /**
     * Checks if the system has already been initialized.
     *
     * Determines initialization status by checking for essential data presence
     * including admin users, shop information, and basic configuration.
     */
    override suspend fun checkInitializationStatus(): InitialSetupDto.InitialSetupStatusDto =
        dbQuery {
            // Check for essential data presence (FIRST + SECOND priorities)
            val hasAdminUsers =
                Employee
                    .find {
                        EmployeeTable.role eq EmployeeRole.ADMIN
                    }.count() > 0

            val hasShopInfo = ShopInfo.all().count() > 0

            // Check if shop configuration exists by checking for any settings
            val hasShopConfiguration =
                if (hasShopInfo) {
                    val firstShop = ShopInfo.all().limit(1).firstOrNull()
                    firstShop?.let {
                        configurationRepository.getAllSettings(it.id.value).isNotEmpty()
                    } ?: false
                } else {
                    false
                }

            // System is initialized if all essential data exists
            val isInitialized = hasAdminUsers && hasShopInfo && hasShopConfiguration

            // Collect detailed system state for status response
            val categoryCount = ProductCategory.all().count()
            val productCount = Product.all().count()
            val tableCount = RTable.all().count()
            val hasVatRates = Vat.all().count() > 0
            val hasPaymentTypes = PaymentType.all().count() > 0

            val systemState =
                InitialSetupDto.SystemStateDto(
                    hasAdminUsers = hasAdminUsers,
                    hasShopInfo = hasShopInfo,
                    hasShopConfig = hasShopConfiguration,
                    categoryCount = categoryCount,
                    productCount = productCount,
                    tableCount = tableCount,
                    hasVatRates = hasVatRates,
                    hasPaymentTypes = hasPaymentTypes,
                )

            InitialSetupDto.InitialSetupStatusDto(
                isInitialized = isInitialized,
                systemState = systemState,
                requiresSetup = !isInitialized,
            )
        }

    /**
     * Resets the system to its initial state.
     *
     * WARNING: This operation removes all data and reinitializes the system.
     * Only available with proper confirmation code.
     */
    override suspend fun resetSystemData(resetRequest: InitialSetupDto.ResetSystemRequestDto): InitialSetupDto.InitialSetupResponseDto {
        val startTime = Clock.System.now().toEpochMilliseconds()

        try {
            resetRequest.validate()
        } catch (e: IllegalArgumentException) {
            logger.warn("resetSystemData: invalid reset request, error={}", e.message)
            throw validationFailed(e.message ?: "Invalid reset request")
        }

        logger.warn(
            "resetSystemData: system reset requested, reason={}",
            resetRequest.resetReason ?: "Not specified",
        )

        dbQuery {
            // Clear all tables in reverse dependency order
            if (!resetRequest.preserveShopInfo) {
                ShopInfoTable.deleteAll()
            }
            if (!resetRequest.preserveAdminUsers) {
                EmployeeTable.deleteAll()
            }
            ProductTable.deleteAll()
            ProductCategoryTable.deleteAll()
            ProductExtrasTable.deleteAll()
            RTableTable.deleteAll()
            VatTable.deleteAll()
            PaymentTypeTable.deleteAll()
            logger.info("resetSystemData: system data reset completed")
        }

        val endTime = Clock.System.now().toEpochMilliseconds()
        val duration = endTime - startTime

        return InitialSetupDto.InitialSetupResponseDto(
            success = true,
            message = "System reset completed successfully",
            setupSummary =
                InitialSetupDto.SetupSummaryDto(
                    shopInfoConfigured = false,
                ),
            warnings =
                if (resetRequest.preserveAdminUsers || resetRequest.preserveShopInfo) {
                    listOf("Some data was preserved as requested")
                } else {
                    emptyList()
                },
            completedAt = Clock.System.now().toString(),
            durationMs = duration,
        )
    }

    override suspend fun executeSetupStep(setupRequest: InitialSetupDto.InitialSetupRequestDto<*>) {
        val stepId = setupRequest.setupStep.stepId
        logger.info(
            "executeSetupStep: executing setup step {} (ID: {})",
            setupRequest.setupStep.name,
            stepId,
        )

        val setupStep =
            SetupSteps.getByStepId(stepId)
                ?: throw validationFailed("Invalid setup step id: $stepId")

        try {
            when (setupStep) {
                SetupSteps.OWNER_SETUP -> {
                    val data =
                        setupRequest.data as? InitialSetupDto.OwnerSetupRequestDto
                            ?: throw validationFailed("Expected OwnerSetupRequestDto for OWNER_SETUP")
                    executeOwnerSetup(data)
                }

                SetupSteps.SHOP_SETUP -> {
                    val data =
                        setupRequest.data as? InitialSetupDto.ShopSetupRequestDto
                            ?: throw validationFailed("Expected ShopSetupRequestDto for SHOP_SETUP")
                    executeShopSetup(data)
                }

                SetupSteps.PAYMENT_TYPES_SETUP -> {
                    val data =
                        setupRequest.data as? InitialSetupDto.PaymentTypesSetupRequestDto
                            ?: throw validationFailed("Expected PaymentTypesSetupRequestDto for PAYMENT_TYPES_SETUP")
                    executePaymentTypesSetup(data)
                }

                SetupSteps.VAT_SETUP -> {
                    val data =
                        setupRequest.data as? InitialSetupDto.VatSetupRequestDto
                            ?: throw validationFailed("Expected VatSetupRequestDto for VAT_SETUP")
                    executeVatSetup(data)
                }

                SetupSteps.TSE_MODE_SETUP -> {
                    val data =
                        setupRequest.data as? InitialSetupDto.TseSetupRequestDto
                            ?: throw validationFailed("Expected TseSetupRequestDto for TSE_MODE_SETUP")
                    executeTseSetup(data)
                }

                SetupSteps.SERVER_CONFIG_SETUP -> {
                    val data =
                        setupRequest.data as? InitialSetupDto.ServerConfigSetupRequestDto
                            ?: throw validationFailed("Expected ServerConfigSetupRequestDto for SERVER_CONFIG_SETUP")
                    executeServerConfigSetup(data)
                }
            }
            logger.info(
                "executeSetupStep: step {} completed successfully",
                setupRequest.setupStep.name,
            )
        } catch (e: ClassCastException) {
            logger.error("executeSetupStep: invalid data type for setup step", e)
            throw validationFailed(e.message ?: "Invalid data type for setup step")
        }
    }

    /**
     * Executes Step 1: Owner/Administrator Setup.
     *
     * Creates the initial system administrator account with full permissions.
     *
     * Idempotency is keyed on **the requested username**, not on "some ADMIN exists". The wider
     * guard silently skipped the step whenever any admin row was present — including the `dev-user`
     * account a `JWT_DEVELOPMENT_MODE` boot seeds — and `executeCompleteSetup` still answered
     * `{"success": true}`, so the operator got an install whose chosen credentials return 401 with
     * nothing in the response to say why. A username that is already taken is the one case where
     * re-running the step genuinely has nothing to do.
     */
    private suspend fun executeOwnerSetup(request: InitialSetupDto.OwnerSetupRequestDto) =
        dbQuery {
            if (employeeRepository.getEmployeeByUsername(request.adminUsername) != null) {
                logger.info("executeOwnerSetup: already completed (employee '{}' exists)", request.adminUsername)
                return@dbQuery
            }

            try {
                request.validate()
            } catch (e: IllegalArgumentException) {
                throw validationFailed(e.message ?: "Invalid owner setup request")
            }

            val ownerUser =
                EmployeeDto.CreateEmployeeRequestDto(
                    username = request.adminUsername,
                    password = hashPassword(request.adminPassword),
                    email = request.adminEmail,
                    // The wizard collects a PIN and it used to be dropped here, so the owner could
                    // never unlock the till with the PIN they had just chosen.
                    pin = request.adminPin,
                    role = EmployeeRole.ADMIN.toString(),
                    languagePreference = request.languagePreference,
                )

            val employeeNumber = employeeRepository.getNextEmployeeNumber("A")
            val owner = employeeRepository.createEmployee(ownerUser, employeeNumber)
            owner.employeeInfo = createOwnerPersonalRecord(owner.id.value, request)
            logger.info("executeOwnerSetup: step 1 (Owner Setup) completed successfully")
        }

    /**
     * Writes the owner's name into `employee_info`, which the wizard collected and then dropped.
     *
     * The name is not decoration: `employee_info.first_name`/`last_name` are where DSFinV-K's
     * `BEDIENER_NAME` comes from, so without this record every receipt the owner signs names
     * nobody. That is also why [EmployeeInfoDto.CreateEmployeeInfoRequestDto.validate] enforces the
     * combined length — it is a fiscal field, and its refusal must reach the operator as a 400
     * rather than a 500 at the end of the wizard.
     *
     * The caller must also link the returned record as `employee.employeeInfo`: the export reads
     * the name through `employees.info_id`, so a record that exists but is not linked still exports
     * the username (E2E round 4, R4-0) — the step `EmployeeInfoServiceImpl` performs on every other
     * path.
     *
     * Requires an ambient transaction.
     */
    private fun createOwnerPersonalRecord(
        ownerId: UUID,
        request: InitialSetupDto.OwnerSetupRequestDto,
    ): EmployeeInfo =
        try {
            employeeInfoRepository.createEmployeeInfo(
                EmployeeInfoDto.CreateEmployeeInfoRequestDto(
                    employeeId = ownerId.toString(),
                    firstName = request.adminFirstName.trim(),
                    lastName = request.adminLastName.trim(),
                    email = request.adminEmail,
                ),
            )
        } catch (e: IllegalArgumentException) {
            throw validationFailed(e.message ?: "Invalid owner name")
        }

    /**
     * Executes Step 2: Shop Information Setup.
     */
    private suspend fun executeShopSetup(request: InitialSetupDto.ShopSetupRequestDto) =
        dbQuery {
            val hasShopInfo = ShopInfo.all().count() > 0
            if (hasShopInfo) {
                logger.info("executeShopSetup: already completed (shop info exists)")
                return@dbQuery
            }

            try {
                request.validate()
            } catch (e: IllegalArgumentException) {
                throw validationFailed(e.message ?: "Invalid shop setup request")
            }

            val shopInfoRequest =
                ShopInfoDto.CreateShopInfoRequestDto(
                    shopName = request.shopName,
                    companyName = request.companyName,
                    shopAddress = request.shopAddress,
                    shopCity = request.shopCity,
                    zipCode = request.zipCode,
                    shopCountry = request.shopCountry,
                    shopPhone = request.shopPhone,
                    shopTaxNumber = request.shopTaxNumber,
                    ustIdNr = request.ustIdNr,
                    shopBankName = request.shopBankName,
                    shopBankBic = request.shopBankBic,
                    shopBankIBAN = request.shopBankIBAN,
                    shopEmail = request.shopEmail,
                    shopTimezone = request.shopTimezone,
                    shopWebsite = request.shopWebsite,
                    currencyCode = request.currencyCode,
                )

            val shop = shopInfoRepository.create(shopInfoRequest)

            // The fiscal Ladenkasse exists from the moment the shop does. It used to be created
            // lazily, the first time something asked for the fiscal book, so a fresh install's
            // back office listed only e.g. a Tresor until the first sale or Kassenbuch read
            // (E2E 2026-09-20 observation). Idempotent, and in the same transaction as the shop.
            cashBookRepository.fiscalBookFor(shop)

            // Initialize default configuration for the new shop
            configurationRepository.initializeDefaults(shop.id.value)

            // Persist the currency the wizard collected. There is no currency column on the shop
            // table — ShopInfoRepositoryImpl.create() silently drops CreateShopInfoRequestDto's
            // currencyCode — so ConfigKey.DEFAULT_CURRENCY is where it has to live; that is what
            // ShopConfigurationManager.getDefaultCurrency() reads, and what money responses carry.
            // Must run AFTER initializeDefaults, which seeds every key and would overwrite it with
            // the seeded {"currency": "EUR"}. The value must stay a well-formed JSON object:
            // setSetting stores unparseable JSON as a JSON *string*, and the later read would then
            // silently fall back.
            // Only overwrite the seeded {"currency": "EUR"} when the wizard actually collected a
            // code. Writing a blank one through would leave every money response currency-less.
            if (request.currencyCode.isNotBlank()) {
                configurationRepository.setSetting(
                    shopId = shop.id.value,
                    configKey = ConfigKey.DEFAULT_CURRENCY,
                    value = """{"currency": "${request.currencyCode}"}""",
                )
            }

            // Generate Kassenmeldung config with unique serial number
            val serialNumber = KasseSerialGenerator.generate()

            @Suppress("ktlint:standard:max-line-length") // single-line fiscal kasse config JSON
            val kassenConfigJson = """{"kasseSeriennr":"$serialNumber","kasseBrand":"${BuildConfig.APP_NAME}","kasseModell":"Desktop/Server POS","kasseSwBrand":"${BuildConfig.APP_NAME}","kasseSwVersion":"${BuildConfig.APP_VERSION}","artDesAufzeichnungssystems":"Computergestützte/PC-Kassensysteme","inbetriebnahme":"${java.time.LocalDate.now()}"}"""
            configurationRepository.setSetting(
                shopId = shop.id.value,
                configKey = ConfigKey.KASSEN_MELDUNG_CONFIG,
                value = kassenConfigJson,
            )

            // Drop the 30-minute config cache so the freshly written currency is visible immediately,
            // as the TSE and server-config steps already do after their own setSetting calls.
            configManager.refreshConfiguration()

            logger.info("executeShopSetup: step 2 (Shop Setup) completed successfully")
        }

    /**
     * Executes Step 3: Payment Types Setup.
     */
    private suspend fun executePaymentTypesSetup(request: InitialSetupDto.PaymentTypesSetupRequestDto) =
        dbQuery {
            val hasPaymentTypes = PaymentType.all().count() > 0
            if (hasPaymentTypes) {
                logger.info("executePaymentTypesSetup: already completed (payment types exist)")
                return@dbQuery
            }

            try {
                request.validate()
            } catch (e: IllegalArgumentException) {
                throw validationFailed(e.message ?: "Invalid payment types setup request")
            }

            val createdPaymentTypes = mutableListOf<String>()

            if (request.useSamplePaymentTypes) {
                val created = createSamplePaymentType()
                createdPaymentTypes += created.map { it.name }
            }

            var hasErrors = false
            request.paymentTypes.forEach { paymentTypeName ->
                try {
                    paymentTypeRepository.createPaymentType(
                        name = paymentTypeName,
                        isActive = true,
                    )
                    createdPaymentTypes.add(paymentTypeName)
                    logger.info("executePaymentTypesSetup: created payment type={}", paymentTypeName)
                } catch (e: Exception) {
                    logger.warn(
                        "executePaymentTypesSetup: failed to create payment type={}: {}",
                        paymentTypeName,
                        e.message,
                    )
                    hasErrors = true
                }
            }

            if (hasErrors) {
                throw PosException(
                    PosErrorCode.INTERNAL_ERROR,
                    HttpStatusCode.InternalServerError,
                    "Some payment types could not be created",
                )
            }
            logger.info("executePaymentTypesSetup: step 3 (Payment Types) completed (${createdPaymentTypes.size} types)")
        }

    /**
     * Executes Step 4: VAT Setup.
     */
    private suspend fun executeVatSetup(request: InitialSetupDto.VatSetupRequestDto) =
        dbQuery {
            val hasVatRates = Vat.all().count() > 0
            if (hasVatRates) {
                logger.info("executeVatSetup: already completed (VAT rates exist)")
                return@dbQuery
            }

            try {
                request.validate()
            } catch (e: IllegalArgumentException) {
                throw validationFailed(e.message ?: "Invalid VAT setup request")
            }

            var vatRatesCreated = 0
            if (request.createStandardGermanRates) {
                vatRepository.setStandardGermanRates()
                vatRatesCreated = 3
                logger.info("executeVatSetup: created standard German VAT rates")
            }

            request.customRates.forEach { customRate ->
                // STAM-4: the wizard's custom-rate path creates rates DIRECTLY via the repository,
                // bypassing VatServiceImpl — apply the same single-source mappability guard here so
                // an unmappable custom rate can't slip in (would export under UST_SCHLUESSEL 7 with a
                // non-zero USt while vat.csv declares key 7 = 0,00 %).
                VatRateGuard.requireMappable(customRate.vatRate)
                vatRepository.create(
                    vatId = customRate.vatClass,
                    vatClass = customRate.description?.uppercase() ?: "CUSTOM",
                    vatRate = toBigDecimal(customRate.vatRate),
                )
                vatRatesCreated++
                logger.info("executeVatSetup: created custom VAT rate ${customRate.vatClass} at ${customRate.vatRate}")
            }
            logger.info("executeVatSetup: step 4 (VAT Setup) completed ($vatRatesCreated rates)")
        }

    /**
     * Executes Step 5: TSE Mode Setup.
     */
    private suspend fun executeTseSetup(request: InitialSetupDto.TseSetupRequestDto) =
        dbQuery {
            val shop = ShopInfo.all().limit(1).firstOrNull()
            val hasTseConfig =
                if (shop != null) {
                    configurationRepository.getSetting(shop.id.value, ConfigKey.TSE_CONFIG.key) != null
                } else {
                    false
                }
            if (hasTseConfig) {
                logger.info("executeTseSetup: already completed (TSE config exists)")
                return@dbQuery
            }

            try {
                request.validate()
            } catch (e: IllegalArgumentException) {
                throw validationFailed(e.message ?: "Invalid TSE setup request")
            }

            val tseMode =
                try {
                    TseMode.valueOf(request.tseMode)
                } catch (e: IllegalArgumentException) {
                    throw validationFailed("Invalid TSE mode '${request.tseMode}'. Valid values: SIMULATION, NORMAL, DISABLED")
                }

            val firstShop =
                ShopInfo.all().limit(1).firstOrNull()
                    ?: throw setupIncomplete("No shop found. Complete shop setup first.")

            logger.debug("executeTseSetup: looking for shop configuration for shop ID: ${firstShop.id}")

            val settings = configurationRepository.getAllSettings(firstShop.id.value)
            if (settings.isEmpty()) {
                logger.info("executeTseSetup: initializing default configuration for shop ID: ${firstShop.id}")
                configurationRepository.initializeDefaults(firstShop.id.value)
            }

            val tseConfig =
                TseConfigDto.TseConfig(
                    mode = tseMode,
                    timeAdminPin = "12345",
                )
            val tseConfigJson = Json.encodeToString(TseConfigDto.TseConfig.serializer(), tseConfig)

            configurationRepository.setSetting(
                shopId = firstShop.id.value,
                configKey = ConfigKey.TSE_CONFIG,
                value = tseConfigJson,
            )

            logger.info(
                "executeTseSetup: updated shop '${firstShop.shopName}' configuration with TSE mode: ${tseMode.name}",
            )

            configManager.refreshConfiguration()
            logger.info("executeTseSetup: step 5 (TSE Mode Setup) completed with mode: ${tseMode.name}")
        }

    /**
     * Executes Step 6: Server Configuration Setup.
     */
    private suspend fun executeServerConfigSetup(request: InitialSetupDto.ServerConfigSetupRequestDto) =
        dbQuery {
            val firstShop = ShopInfo.all().limit(1).firstOrNull()
            val hasServerConfig =
                if (firstShop != null) {
                    configurationRepository.getSetting(firstShop.id.value, ConfigKey.SERVER_PORT.key) != null
                } else {
                    false
                }

            if (hasServerConfig) {
                logger.info("executeServerConfigSetup: already completed (server port configured)")
                return@dbQuery
            }

            try {
                request.validate()
            } catch (e: IllegalArgumentException) {
                throw validationFailed(e.message ?: "Invalid server configuration")
            }

            if (firstShop == null) {
                throw setupIncomplete("No shop found. Complete shop setup first.")
            }

            logger.info("executeServerConfigSetup: configuring server settings for shop ID: ${firstShop.id}")

            configurationRepository.setSetting(
                shopId = firstShop.id.value,
                configKey = ConfigKey.SERVER_PORT,
                value = """{"port":${request.serverPort}}""",
            )
            logger.info("executeServerConfigSetup: set server port: ${request.serverPort}")

            configManager.refreshConfiguration()
            logger.info("executeServerConfigSetup: step 6 (Server Configuration) completed")
        }

    /**
     * Executes a complete atomic system setup from the UI wizard.
     *
     * Performs all initialization in sequence:
     * 1. Creates admin account (from user input)
     * 2. Creates shop info (from user input)
     * 3. Initializes all ConfigKey defaults (auto)
     * 4. Creates sample payment types (auto)
     * 5. Creates standard German VAT rates (auto)
     *
     * If any step fails, the in-flight transaction rolls back via PosException propagation.
     */
    override suspend fun executeCompleteSetup(request: InitialSetupDto.CompleteSetupRequestDto): InitialSetupDto.InitialSetupResponseDto {
        val startTime = Clock.System.now().toEpochMilliseconds()

        // Guard: reject if system is already initialized
        val status = checkInitializationStatus()
        if (status.isInitialized) {
            throw alreadyProvisioned()
        }

        // Validate both DTOs upfront before starting transaction
        try {
            request.validate()
        } catch (e: IllegalArgumentException) {
            throw validationFailed(e.message ?: "Invalid setup data")
        }

        // Step 1: Create admin account
        executeOwnerSetup(request.owner)
        logger.info("executeCompleteSetup: owner account created")

        // Step 2: Create shop info + initialize all ConfigKey defaults
        executeShopSetup(request.shop)
        logger.info("executeCompleteSetup: shop info created with defaults")

        dbQuery {
            // Step 3: Create default payment types
            createSamplePaymentType()
            logger.info("executeCompleteSetup: default payment types created")

            // Step 4: Create standard German VAT rates
            vatRepository.setStandardGermanRates()
            logger.info("executeCompleteSetup: standard German VAT rates created")
        }

        val endTime = Clock.System.now().toEpochMilliseconds()
        logger.info("executeCompleteSetup: finished successfully in ${endTime - startTime}ms")

        return InitialSetupDto.InitialSetupResponseDto(
            success = true,
            message = "System initialized successfully",
            setupSummary =
                InitialSetupDto.SetupSummaryDto(
                    shopInfoConfigured = true,
                ),
            completedAt = Clock.System.now().toString(),
            durationMs = endTime - startTime,
        )
    }

    private fun createSamplePaymentType(): List<PaymentType> {
        val defaultPaymentTypes = PaymentDto.getDefaultPaymentTypes()
        val paymentTypes = mutableListOf<PaymentType>()
        defaultPaymentTypes.forEach { payment ->
            val paymentType =
                paymentTypeRepository.createPaymentType(
                    name = payment.name,
                    en = payment.en,
                    ch = payment.ch,
                    isActive = payment.isActive,
                    isCash = payment.isCash,
                    createsReceivable = payment.createsReceivable,
                )
            logger.info("createSamplePaymentType: created default payment type: ${payment.name}")
            paymentTypes.add(paymentType)
        }
        return paymentTypes
    }

    // --- PosException helpers ---

    private fun alreadyProvisioned() =
        PosException(
            PosErrorCode.ALREADY_PROVISIONED,
            HttpStatusCode.Conflict,
            "System is already provisioned",
        )

    private fun setupIncomplete(missing: String) =
        PosException(
            PosErrorCode.SETUP_INCOMPLETE,
            HttpStatusCode.Conflict,
            "Setup incomplete: $missing",
        )

    private fun validationFailed(msg: String) =
        PosException(
            PosErrorCode.VALIDATION_FAILED,
            HttpStatusCode.BadRequest,
            msg,
        )

    /**
     * Reads readiness entirely from persisted state — **it never probes the TSE device.**
     *
     * A dashboard card must not block on fiscal hardware, and per the design this card is an
     * onboarding aid rather than a health monitor: the TSE screen owns live device health. So
     * [SetupReadinessDto.tseCommissioned] is derived from the operator's configured mode plus the
     * presence of a registered TSE client, both of which are database reads.
     *
     * `tseClientRegistered` is a sound proxy for "commissioned" because a client can only be
     * registered after the TSE has been taken into service — the first-terminal flow commissions the
     * TSE, then creates the terminal, then registers its client. A [TseMode.DISABLED] install never
     * passes, no matter what is registered: a German POS that signs nothing is not ready to trade, and
     * a check that can be cleared by switching the requirement off is not a check.
     */
    override suspend fun checkReadiness(): SetupReadinessDto {
        // Read outside the transaction: getTseMode() is a suspend call on the config manager, and
        // suspending inside dbQuery would hold a connection across it.
        val tseMode = configManager.getTseMode()

        return dbQuery {
            val terminals = Terminal.all().toList()
            val hasFiscalTerminal = terminals.any { it.fiscal && it.isActive }
            val hasRegisteredTseClient = terminals.any { it.tseClientRegistered }

            SetupReadinessDto(
                hasFiscalTerminal = hasFiscalTerminal,
                tseCommissioned = tseMode != TseMode.DISABLED && hasRegisteredTseClient,
                hasVatRates = Vat.all().count() > 0,
                hasPaymentTypes = PaymentType.all().count() > 0,
                productCount = Product.all().count(),
                tableCount = RTable.all().count(),
                restaurantMode =
                    ShopInfo
                        .all()
                        .limit(1)
                        .firstOrNull()
                        ?.restaurantMode ?: true,
            )
        }
    }
}
