package org.example.project.testutils

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.constants.WellKnownLocationTypes
import org.example.project.core.domain.dto.tse.TseConfigDto
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.domain.enums.employee.EmploymentStatus
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.product.ProductType
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.domain.enums.reservation.ReservationStatus
import org.example.project.core.domain.enums.scheduler.JobFrequency
import org.example.project.core.domain.enums.scheduler.ScheduledJobType
import org.example.project.core.domain.enums.setup.StartPageTarget
import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.bill.domain.models.BillVatDetail
import org.example.project.features.changelog.domain.models.ChangelogRelease
import org.example.project.features.employee.database.tables.EmployeePermissionGroupTable
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.database.tables.PermissionGroupTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.order.domain.models.OrderItem
import org.example.project.features.payment.domain.models.PaymentType
import org.example.project.features.payment.domain.models.Vat
import org.example.project.features.product.domain.models.Product
import org.example.project.features.product.domain.models.ProductCategory
import org.example.project.features.scheduler.data.repository.ScheduledJobRepositoryImpl
import org.example.project.features.scheduler.domain.models.ScheduledJob
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.table.database.tables.LocationTypeTable
import org.example.project.features.table.domain.models.RTable
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.terminal.database.tables.TerminalTable
import org.example.project.features.terminal.domain.TerminalEmployee
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.tse.domain.models.TseTransaction
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.utils.dbQuery
import org.example.project.utils.formatter.currentTimeStamp
import org.example.project.utils.formatter.hashPassword
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.example.project.features.table.domain.models.LocationType as LocationTypeEntity

data class TableWithSession(
    val table: RTable,
    val session: TableSession,
)

/**
 * Drops the sub-microsecond digits [currentTimeStamp] can carry, leaving the precision a
 * timestamp column actually stores.
 *
 * `Clock.System.now()` resolves to microseconds on macOS but to nanoseconds on Linux, while
 * both H2 and PostgreSQL hold microseconds — so a test that writes a raw `currentTimeStamp()`
 * and then compares it against the value read back passes on a developer's Mac and fails on a
 * CI runner, on a digit no column can keep. Truncate the value on the way in and the comparison
 * measures the round-trip instead of the clock.
 */
fun LocalDateTime.atStoredPrecision(): LocalDateTime = toJavaLocalDateTime().truncatedTo(ChronoUnit.MICROS).toKotlinLocalDateTime()

object TestData {
    /**
     * Funds cash book [cashBookId] (null = the shop's fiscal Ladenkasse) with [amount]: one plain
     * CASH_DEPOSIT written straight through the repository — no document, no terminal, no TSE
     * signature, so it leaves a test's captured signing payloads, audit rows and Eigenbelege untouched.
     *
     * F59: the Kassenminus guard refuses any outflow a book cannot cover. A test that books an outflow
     * only to observe something else about it (its signature, its DSFinV-K row, its date handling)
     * funds the book first — the balance is not what it asserts.
     */
    fun fundCashBook(
        amount: String,
        cashBookId: Long? = null,
    ) {
        transaction {
            val shop = ShopInfo.all().first()
            val employee = Employee.all().first()
            val repository =
                org.example.project.features.kassenbuch.data.repository
                    .KassenbuchRepositoryImpl(testKassenbuchDocuments())
            val today =
                org.example.project.utils.formatter
                    .currentTimeStamp()
                    .date
            repository.createEntry(
                shop = shop,
                employee = employee,
                headerData =
                    org.example.project.core.domain.dto.kassenbuch.KassenbuchDto.KassenbuchData(
                        entryType = org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType.CASH_DEPOSIT,
                        belegNr =
                            repository.nextBelegNrBlocking(
                                cashBookId
                                    ?: org.example.project.features.kassenbuch.data.repository
                                        .CashBookRepositoryImpl()
                                        .fiscalBookFor(shop)
                                        .id.value,
                            ),
                        vorzBetrag = amount,
                        belegDatum =
                            "${today.dayOfMonth.toString().padStart(2, '0')}.${today.monthNumber.toString().padStart(2, '0')}.${today.year}",
                        belegtext = "Einlage (Testfixture)",
                        cashBookId = cashBookId,
                    ),
                lineItemsData =
                    listOf(
                        org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
                            .LineItemData(vorzBetrag = amount, ustSatz = null),
                    ),
            )
        }
    }

    const val DEFAULT_PASSWORD = "test1234"

    fun ensureLocationTypes() {
        transaction {
            if (LocationTypeEntity.count() == 0L) {
                val now = currentTimeStamp()

                data class LtSeed(
                    val name: String,
                    val en: String,
                    val cn: String,
                    val virtual: Boolean,
                    val takeaway: Boolean,
                    val sort: Int,
                    val system: Boolean = false,
                )
                listOf(
                    LtSeed("Innenbereich", "Indoor", "室内", false, false, 1),
                    LtSeed("Außenbereich", "Outdoor", "户外", false, false, 2),
                    LtSeed("Abholung", "Takeaway", "外卖", false, true, 3),
                    LtSeed("Gutschein", "Voucher", "代金券", true, false, 4, system = true),
                    LtSeed("Schnellverkauf", "Quick Sale", "快速销售", true, false, 5, system = true),
                ).forEach { seed ->
                    LocationTypeTable.insertIgnore {
                        it[LocationTypeTable.name] = seed.name
                        it[englishName] = seed.en
                        it[chineseName] = seed.cn
                        it[isVirtual] = seed.virtual
                        it[isTakeaway] = seed.takeaway
                        it[sortOrder] = seed.sort
                        it[isSystem] = seed.system
                        it[createdAt] = now
                        it[updatedAt] = now
                    }
                }
            }
        }
    }

    fun createScheduledJob(
        type: ScheduledJobType = ScheduledJobType.ENTRY_LOG_RETENTION,
        enabled: Boolean = false,
        nextRunAt: Instant = Instant.now().plusSeconds(3600),
    ): ScheduledJob =
        transaction {
            val now = Instant.now()
            ScheduledJobRepositoryImpl().create(
                ScheduledJob(
                    id = UUID.randomUUID(),
                    name = "Test ${type.name}",
                    type = type,
                    enabled = enabled,
                    frequency = JobFrequency.DAILY,
                    timeOfDay = "03:00",
                    dayOfWeek = null,
                    dayOfMonth = null,
                    parametersJson = "{}",
                    timezone = "Europe/Berlin",
                    nextRunAt = nextRunAt,
                    lastRunAt = null,
                    lastStatus = null,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }

    fun createAdmin(
        employeeNumber: String = "ADM001",
        username: String = "admin",
        password: String = DEFAULT_PASSWORD,
    ): Employee =
        createEmployee(
            employeeNumber = employeeNumber,
            username = username,
            password = password,
            role = EmployeeRole.ADMIN,
        )

    fun createManager(
        employeeNumber: String = "MGR001",
        username: String = "manager",
        password: String = DEFAULT_PASSWORD,
    ): Employee =
        createEmployee(
            employeeNumber = employeeNumber,
            username = username,
            password = password,
            role = EmployeeRole.MANAGER,
        )

    fun createCashier(
        employeeNumber: String = "CSH001",
        username: String = "cashier",
        password: String = DEFAULT_PASSWORD,
    ): Employee =
        createEmployee(
            employeeNumber = employeeNumber,
            username = username,
            password = password,
            role = EmployeeRole.CASHIER,
        )

    /**
     * A TERMINAL_DEVICE-role principal. TERMINAL_DEVICE is also the fallback [EmployeeRole.getRole] resolves an
     * unrecognized role string to, so it is the weakest principal that still passes JWT validation —
     * useful for pinning that role-gated routes reject it.
     */
    fun createTerminal(
        employeeNumber: String = "TRM001",
        username: String = "terminal",
        password: String = DEFAULT_PASSWORD,
    ): Employee =
        createEmployee(
            employeeNumber = employeeNumber,
            username = username,
            password = password,
            role = EmployeeRole.TERMINAL_DEVICE,
        )

    fun createInactiveEmployee(
        employeeNumber: String = "INA001",
        username: String = "inactive",
        password: String = DEFAULT_PASSWORD,
    ): Employee =
        createEmployee(
            employeeNumber = employeeNumber,
            username = username,
            password = password,
            role = EmployeeRole.TERMINAL_DEVICE,
            status = EmploymentStatus.INACTIVE,
        )

    /**
     * Seeds a [Terminal] device row that clears the terminal-capability ceiling in
     * [org.example.project.infrastructure.plugins.RouteAuthorizationPlugin] — paired, active,
     * fiscal, and TSE-registered — for [deviceId].
     *
     * Required by any HTTP test that calls one of the exact transactional routes in
     * [org.example.project.infrastructure.security.TransactionalRoutes] (POST /api/orders, POST
     * /api/items/cancel/{sourceTableId}, POST /api/v1/bills, DELETE /api/v1/bills/{id}): pair this with
     * `TestAuthHelper.loginAs(client, ..., deviceId = deviceId)` (or a raw `X-Device-ID` header
     * matching [deviceId] on the request) so the request's trusted identity resolves to this
     * terminal. Idempotent per [deviceId].
     */
    fun createFiscalRegisteredTerminal(
        deviceId: String,
        clientId: String,
        role: String = "CASHIER",
    ): Terminal =
        transaction {
            Terminal.find { TerminalTable.deviceId eq deviceId }.firstOrNull()
                ?: Terminal.new {
                    this.deviceId = deviceId
                    this.clientId = clientId
                    this.role = role
                    this.fiscal = true
                    this.tseClientRegistered = true
                    this.isActive = true
                    this.registeredAt = currentTimeStamp()
                    this.lastSeenAt = currentTimeStamp()
                }
        }

    /**
     * Seeds a shop and pins the TSE mode for this test's database.
     *
     * Set explicitly in EVERY test, not only the one that wants NORMAL: `ShopConfigurationManager`
     * caches process-wide, so a class-mate that set NORMAL would otherwise leak into whichever test
     * JUnit happens to run next and turn its expected 200 into a 424.
     */
    fun setTseMode(mode: TseMode) {
        createShopInfo()
        runBlocking {
            ShopConfigurationManager.setTseConfig(TseConfigDto.TseConfig(timeAdminPin = "12345", mode = mode))
        }
    }

    /**
     * Turns round-robin assignment on or off exactly where the running server reads it.
     *
     * Writes ORDER_RULES on the FIRST shop — `ShopConfigurationManager` loads its configuration from
     * `ShopInfo.all().limit(1)`, and [createShopInfo] is not idempotent, so writing to a freshly created
     * shop can land on a row nobody reads — then clears the rotation cursor and refreshes the
     * process-wide configuration cache. That cache outlives the per-test database: a leg that needs
     * round-robin OFF must call this with `false` rather than rely on the default.
     *
     * Overwrites the WHOLE ORDER_RULES setting, not just `roundRobinAssignment` — `employeeSelectionOnFreeTable`
     * is always written back as `false` regardless of what it was before this call.
     */
    suspend fun setRoundRobinAssignment(enabled: Boolean) {
        val existingShop = transaction { ShopInfo.all().limit(1).firstOrNull() }
        val shopId = existingShop?.id?.value ?: createShopInfo().id.value
        dbQuery {
            ConfigurationRepository().setSetting(
                shopId = shopId,
                configKey = ConfigKey.ORDER_RULES,
                value = """{"employeeSelectionOnFreeTable":false,"roundRobinAssignment":$enabled}""",
            )
        }
        ShopConfigurationManager.setOrderRotationCursor(null)
        ShopConfigurationManager.refreshConfiguration()
    }

    /**
     * Sets which terminal roles may run as browser stations, exactly where the running server reads it.
     *
     * Same shape and same trap as [setRoundRobinAssignment]: writes BROWSER_STATIONS on the FIRST shop and
     * refreshes the process-wide `ShopConfigurationManager` cache, which outlives the per-test database —
     * so every test that cares must set it explicitly, `emptySet()` included.
     */
    suspend fun setBrowserStationRoles(roles: Set<TerminalRole>) {
        val existingShop = transaction { ShopInfo.all().limit(1).firstOrNull() }
        val shopId = existingShop?.id?.value ?: createShopInfo().id.value
        val array = roles.joinToString(",") { "\"${it.name}\"" }
        dbQuery {
            ConfigurationRepository().setSetting(
                shopId = shopId,
                configKey = ConfigKey.BROWSER_STATIONS,
                value = """{"roles":[$array]}""",
            )
        }
        ShopConfigurationManager.refreshConfiguration()
    }

    suspend fun setApiDocsEnabled(enabled: Boolean) = writeWebAccess(ConfigKey.API_DOCS_ENABLED, enabled)

    suspend fun setStartPageDefault(target: StartPageTarget) = writeWebAccess(ConfigKey.START_PAGE_DEFAULT, target.wire)

    /** The config cache is process-wide and outlives each test DB — every test that reads these must set them. */
    suspend fun clearWebAccessSettings() {
        setApiDocsEnabled(false)
        setStartPageDefault(StartPageTarget.NONE)
    }

    private suspend fun writeWebAccess(
        key: ConfigKey,
        value: Any,
    ) {
        val existingShop = transaction { ShopInfo.all().limit(1).firstOrNull() }
        val shopId = existingShop?.id?.value ?: createShopInfo().id.value
        dbQuery { ConfigurationRepository().setSetting(shopId = shopId, configKey = key, value = value) }
        ShopConfigurationManager.refreshConfiguration()
    }

    /**
     * Runs [block] with round-robin assignment turned on (or off, per [enabled]), then resets it back to
     * `false` in a `finally` — [setRoundRobinAssignment] writes through `ShopConfigurationManager`'s
     * process-wide cache, which outlives this test's database, so a leg that turns rotation ON must not
     * leave it ON for whichever test class JUnit happens to run next.
     */
    suspend fun <T> withRoundRobinAssignment(
        enabled: Boolean,
        block: suspend () -> T,
    ): T {
        setRoundRobinAssignment(enabled)
        try {
            return block()
        } finally {
            setRoundRobinAssignment(false)
        }
    }

    /** Seeds the single shared terminal-employee used for login-less order-station attribution. Idempotent. */
    fun createTerminalDeviceEmployee(): Employee =
        transaction {
            Employee.find { EmployeeTable.username eq TerminalEmployee.USERNAME }.firstOrNull()
                ?: Employee
                    .new {
                        this.employeeNumber = "TERMINAL-DEVICE"
                        this.username = TerminalEmployee.USERNAME
                        this.passwordHash = ""
                        this.role = EmployeeRole.TERMINAL_DEVICE
                        this.employmentStatus = EmploymentStatus.ACTIVE
                        this.languagePreference = "DE"
                        this.trainingMode = false
                    }.also { assignRoleGroup(it, EmployeeRole.TERMINAL_DEVICE) }
        }

    /**
     * Removes the shared terminal-device employee that bootstrap seeds.
     *
     * A fresh install now provisions that row (`SchemaManager.seedTerminalDeviceEmployee`), so "the
     * install is broken" can no longer be expressed by simply not creating it — the row has to be
     * taken away on purpose. Only the broken-install test wants this.
     */
    fun removeTerminalDeviceEmployee() {
        transaction {
            EmployeeTable.deleteWhere { EmployeeTable.username eq TerminalEmployee.USERNAME }
        }
    }

    private fun createEmployee(
        employeeNumber: String,
        username: String,
        password: String,
        role: EmployeeRole,
        status: EmploymentStatus = EmploymentStatus.ACTIVE,
    ): Employee {
        val hashed = runBlocking { hashPassword(password) }
        return transaction {
            val employee =
                Employee.new {
                    this.employeeNumber = employeeNumber
                    this.username = username
                    this.passwordHash = hashed
                    this.role = role
                    this.employmentStatus = status
                    this.languagePreference = "DE"
                    this.trainingMode = false
                }

            assignRoleGroup(employee, role)
            employee
        }
    }

    /**
     * Put a directly-built [Employee] in the permission group its role implies, exactly as
     * `EmployeeRepositoryImpl.assignPermissionGroup` does.
     *
     * **Every fixture that calls `Employee.new` must call this.** The repository is the only
     * production code that assigns a group, so an employee built around it holds NO capabilities —
     * `GroupCapabilityResolver` fails closed by design — and every capability-gated test then fails
     * for a reason that looks nothing like its cause.
     *
     * This has now been the same bug three times: `createEmployee` (fixed in Stage 2a),
     * `createTerminalDeviceEmployee`, and `createDevUser` in production, which left dev mode unable
     * to reach any `Cap` route at all. `NoDirectEmployeeCreationTest` is the standing guard.
     */
    private fun assignRoleGroup(
        employee: Employee,
        role: EmployeeRole,
    ) {
        val groupId =
            PermissionGroupTable
                .selectAll()
                .firstOrNull { it[PermissionGroupTable.code] == role.name }
                ?.get(PermissionGroupTable.id)
                ?: error("no permission_group with code '${'$'}{role.name}' — SchemaManager seeding did not run")

        EmployeePermissionGroupTable.insert {
            it[EmployeePermissionGroupTable.employee] = employee.id
            it[EmployeePermissionGroupTable.group] = groupId
        }
    }

    fun createShopInfo(
        shopName: String = "Test Shop",
        companyName: String? = "Test Company",
        shopAddress: String = "123 Test Street",
        shopCity: String = "Test City",
        zipCode: Int = 12345,
        shopCountry: String = "DE",
        shopPhone: String = "+49 123 456789",
        // A compliant Kassenabschluss MUST carry either a Steuernummer or a USt-IdNr
        // (§ 14 Abs. 4 Nr. 2 UStG, Anhang E p. 68 — round-3 KABS-6). Default the tax
        // number so every seeded shop passes the DSFinV-K export tax-id gate; pass
        // shopTaxNumber = null (optionally with ustIdNr) to exercise the gate itself.
        shopTaxNumber: String? = "12345/67890",
        ustIdNr: String? = null,
    ): ShopInfo =
        transaction {
            ShopInfo.new {
                this.shopName = shopName
                this.companyName = companyName
                this.shopAddress = shopAddress
                this.shopCity = shopCity
                this.zipCode = zipCode
                this.shopCountry = shopCountry
                this.shopPhone = shopPhone
                this.shopTaxNumber = shopTaxNumber
                this.ustIdNr = ustIdNr
                this.createAt = currentTimeStamp()
            }
        }

    /**
     * A Kassenabschluss whose window spans the whole current day. Since the closing-membership
     * fix (audit H3/H4) the export producers SKIP Vorgänge no exported closing covers — tests
     * exercising the producers must hand them a covering closing instead of `emptyList()`.
     */
    fun createZBonFullDay(
        shop: ShopInfo,
        employee: Employee,
        nummer: Long = 1L,
    ): ZBon =
        transaction {
            val now = currentTimeStamp()
            ZBon.new {
                this.shopInfo = shop
                this.employee = employee
                this.startTime = LocalDateTime(now.year, now.month, now.day, 0, 0, 0)
                this.endTime = LocalDateTime(now.year, now.month, now.day, 23, 59, 59)
                this.cancelledAmount = BigDecimal.ZERO
                this.totalDiscounts = BigDecimal.ZERO
                this.totalPosRevenue = BigDecimal.ZERO
                this.adminTip = BigDecimal.ZERO
                this.openingBalance = BigDecimal.ZERO
                this.expenses = BigDecimal.ZERO
                this.cashRevenue = BigDecimal.ZERO
                this.closingBalance = BigDecimal.ZERO
                this.createdAt = now
                this.trainingRevenue = BigDecimal.ZERO
                this.trainingBillCount = 0
                this.refundAmount = BigDecimal.ZERO
                this.stornoBillCount = 0
                this.zbonNummer = nummer
            }
        }

    /**
     * A closed Z-Bon over an explicit `[start, end]` window, for attribution tests where the window
     * crosses a calendar day. A bill is attributed to the day of this Z-Bon's [end], not its own
     * timestamp.
     */
    fun createZBon(
        shop: ShopInfo,
        employee: Employee,
        start: LocalDateTime,
        end: LocalDateTime,
        nummer: Long,
    ): ZBon =
        transaction {
            ZBon.new {
                this.shopInfo = shop
                this.employee = employee
                this.startTime = start
                this.endTime = end
                this.cancelledAmount = BigDecimal.ZERO
                this.totalDiscounts = BigDecimal.ZERO
                this.totalPosRevenue = BigDecimal.ZERO
                this.adminTip = BigDecimal.ZERO
                this.openingBalance = BigDecimal.ZERO
                this.expenses = BigDecimal.ZERO
                this.cashRevenue = BigDecimal.ZERO
                this.closingBalance = BigDecimal.ZERO
                this.createdAt = end
                this.trainingRevenue = BigDecimal.ZERO
                this.trainingBillCount = 0
                this.refundAmount = BigDecimal.ZERO
                this.stornoBillCount = 0
                this.zbonNummer = nummer
            }
        }

    fun createCategory(
        name: String = "Test Category",
        description: String? = null,
        sortPosition: BigDecimal = BigDecimal("1.00000"),
        isActive: Boolean = true,
    ): ProductCategory =
        transaction {
            ProductCategory.new {
                this.name = name
                this.description = description
                this.sortPosition = sortPosition
                this.isActive = isActive
                this.updatedAt = currentTimeStamp()
            }
        }

    fun createProduct(
        categoryId: Int,
        productName: String = "Test Product",
        productNumber: String = "PRD001",
        price: BigDecimal = BigDecimal("9.9900"),
        productType: ProductType = ProductType.FOOD,
        isActive: Boolean = true,
    ): Product =
        transaction {
            val category =
                ProductCategory.findById(categoryId)
                    ?: error("Category with id $categoryId not found")
            Product.new {
                this.productNumber = productNumber
                this.name = productName
                this.price = price
                this.category = category
                this.productType = productType
                this.isActive = isActive
                this.updatedAt = currentTimeStamp()
            }
        }

    fun createDefaultVatRates(): List<Vat> =
        transaction {
            // Fetch-or-create so repeated calls (and cross-class shared-DB state bleed) don't
            // collide on the fixed "A"/"B" primary keys.
            listOf(
                Vat.findById("A") ?: Vat.new("A") {
                    this.vatClass = "Normal"
                    this.vatRate = BigDecimal("0.1900")
                },
                Vat.findById("B") ?: Vat.new("B") {
                    this.vatClass = "Reduced"
                    this.vatRate = BigDecimal("0.0700")
                },
            )
        }

    fun createChangelogRelease(
        releaseVersion: String = "1.0.10",
        titleEn: String = "Version 1.0.10",
        summaryEn: String = "Release notes for 1.0.10",
        highlightsEn: List<String> = listOf("Kassenbuch import"),
        knownIssuesEn: List<String> = listOf("DSFinV-K queue drain"),
    ): ChangelogRelease =
        transaction {
            ChangelogRelease.findById(releaseVersion) ?: ChangelogRelease.new(releaseVersion) {
                this.releaseDate = currentTimeStamp()
                this.titleEn = titleEn
                this.titleDe = titleEn
                this.titleZh = titleEn
                this.summaryEn = summaryEn
                this.summaryDe = summaryEn
                this.summaryZh = summaryEn
                this.highlightsEn = highlightsEn
                this.highlightsDe = highlightsEn
                this.highlightsZh = highlightsEn
                this.knownIssuesEn = knownIssuesEn
                this.knownIssuesDe = knownIssuesEn
                this.knownIssuesZh = knownIssuesEn
                this.appliedAt = currentTimeStamp()
            }
        }

    fun createTableWithSession(
        tableNumber: String = "T1",
        seats: Int = 4,
        locationTypeId: Long = WellKnownLocationTypes.INDOOR,
    ): TableWithSession {
        ensureLocationTypes()
        val employee =
            createCashier(
                employeeNumber = "TBL_$tableNumber",
                username = "tbl_$tableNumber",
            )
        return transaction {
            val locationType =
                LocationTypeEntity.findById(locationTypeId)
                    ?: error("LocationType with id $locationTypeId not found")
            val table =
                RTable.new {
                    this.tableNumber = tableNumber
                    this.seats = seats
                    this.locationTypeRef = locationType
                    this.tableStatus = TableStatus.FREE
                    this.updateAt = currentTimeStamp()
                }
            val session = table.startSession(employee)
            TableWithSession(table, session)
        }
    }

    private var orderItemFixtureCounter = 0

    /** One 5.00 line in [session] with the given [status], on a fresh category + product. */
    fun createOrderItemInSession(
        session: TableSession,
        status: ItemStatus,
    ): OrderItem {
        val n = ++orderItemFixtureCounter
        val vat = createDefaultVatRates().first()
        val category = createCategory("OIS Cat $n")
        val product = createProduct(category.id.value, "OIS Product $n", "OIS_P$n", BigDecimal("5.0000"))
        return transaction {
            OrderItem.new {
                this.session = TableSession.findById(session.id)!!
                this.product = product
                this.unitPrice = BigDecimal("5.00")
                this.finalPrice = BigDecimal("5.00")
                this.itemStatus = status
                this.vat = vat
                this.discount = BigDecimal.ONE
                this.customPrice = false
                this.takeAway = false
                this.transferred = false
                this.sideNotes = null
            }
        }
    }

    fun createPaymentType(
        name: String = "Cash",
        isCash: Boolean = true,
        isActive: Boolean = true,
        isVoucher: Boolean = false,
    ): PaymentType =
        transaction {
            PaymentType.new {
                this.name = name
                this.isCash = isCash
                this.isActive = isActive
                this.isVoucher = isVoucher
                this.updatedAt = currentTimeStamp()
            }
        }

    fun createTable(
        tableNumber: String = "T1",
        seats: Int? = 4,
        locationTypeId: Long = WellKnownLocationTypes.INDOOR,
        isActive: Boolean = true,
        isSystem: Boolean = false,
    ): RTable {
        ensureLocationTypes()
        return transaction {
            val locationType =
                LocationTypeEntity.findById(locationTypeId)
                    ?: error("LocationType with id $locationTypeId not found")
            RTable.new {
                this.tableNumber = tableNumber
                this.seats = seats
                this.locationTypeRef = locationType
                this.tableStatus = TableStatus.FREE
                this.isActive = isActive
                this.isSystem = isSystem
                this.updateAt = currentTimeStamp()
            }
        }
    }

    fun createReservation(
        employee: Employee,
        guestName: String = "Test Guest",
        guestPhone: String? = "123456",
        partySize: Int = 4,
        reservationDate: kotlinx.datetime.LocalDate = kotlinx.datetime.LocalDate(2026, 3, 20),
        reservationTime: kotlinx.datetime.LocalTime = kotlinx.datetime.LocalTime(18, 0),
        durationMinutes: Int? = 120,
        status: ReservationStatus = ReservationStatus.CONFIRMED,
        tableGroups: Int = 1,
        maxTablesPerGroup: Int? = null,
        locationPreference: String? = null,
    ): org.example.project.features.reservation.domain.models.Reservation =
        transaction {
            org.example.project.features.reservation.domain.models.Reservation.new {
                this.guestName = guestName
                this.guestPhone = guestPhone
                this.partySize = partySize
                this.reservationDate = reservationDate
                this.reservationTime = reservationTime
                this.durationMinutes = durationMinutes
                this.status = status
                this.tableGroups = tableGroups
                this.maxTablesPerGroup = maxTablesPerGroup
                this.locationPreference = locationPreference
                this.createdByEmployee = employee
                this.createdAt = currentTimeStamp()
                this.updatedAt = currentTimeStamp()
            }
        }

    fun createOpeningHours(
        dayOfWeek: Int,
        openTime: kotlinx.datetime.LocalTime = kotlinx.datetime.LocalTime(10, 0),
        closeTime: kotlinx.datetime.LocalTime = kotlinx.datetime.LocalTime(22, 0),
        isClosed: Boolean = false,
        shopInfo: ShopInfo? = null,
    ): org.example.project.features.openinghours.domain.models.OpeningHours =
        transaction {
            val shop = shopInfo ?: ShopInfo.all().firstOrNull() ?: createShopInfo()
            org.example.project.features.openinghours.domain.models.OpeningHours.new {
                this.dayOfWeek = dayOfWeek
                this.openTime = openTime
                this.closeTime = closeTime
                this.isClosed = isClosed
                this.shopId = shop.id
                this.createdAt = currentTimeStamp()
            }
        }

    fun createOpeningHoursOverride(
        date: kotlinx.datetime.LocalDate,
        openTime: kotlinx.datetime.LocalTime? = null,
        closeTime: kotlinx.datetime.LocalTime? = null,
        isClosed: Boolean = true,
        reason: String? = null,
        shopInfo: ShopInfo? = null,
    ): org.example.project.features.openinghours.domain.models.OpeningHoursOverride =
        transaction {
            val shop = shopInfo ?: ShopInfo.all().firstOrNull() ?: createShopInfo()
            org.example.project.features.openinghours.domain.models.OpeningHoursOverride.new {
                this.overrideDate = date
                this.openTime = openTime
                this.closeTime = closeTime
                this.isClosed = isClosed
                this.reason = reason
                this.shopId = shop.id
                this.createdAt = currentTimeStamp()
            }
        }

    fun createCombinablePair(
        tableAId: Long,
        tableBId: Long,
    ) = transaction {
        val a = kotlin.math.min(tableAId, tableBId)
        val b = kotlin.math.max(tableAId, tableBId)
        org.example.project.features.table.database.tables.CombinableTablesTable.insert {
            it[org.example.project.features.table.database.tables.CombinableTablesTable.tableAId] = a
            it[org.example.project.features.table.database.tables.CombinableTablesTable.tableBId] = b
        }
    }

    fun createTableAssignment(
        reservationId: Long,
        tableId: Long,
        groupNumber: Int = 1,
        assignedBy: Employee,
    ): org.example.project.features.reservation.domain.models.ReservationTableAssignment =
        transaction {
            org.example.project.features.reservation.domain.models.ReservationTableAssignment.new {
                this.reservation =
                    org.example.project.features.reservation.domain.models.Reservation
                        .findById(reservationId)!!
                this.table = RTable.findById(tableId)!!
                this.groupNumber = groupNumber
                this.assignedByEmployee = assignedBy
                this.assignedAt = currentTimeStamp()
            }
        }

    /**
     * A settled bill with two items, a matching `bill_vat_detail` row and a TSE transaction — the
     * shape slice 4a-3 turns into an invoice.
     *
     * The VAT detail is built **from the items' gross prices**, not typed independently:
     * `BillToInvoiceMapper` refuses a bill whose items do not sum to its fiscal split, so a fixture
     * that hard-coded both would fail for a reason unrelated to the test.
     */
    fun createSettledBillForInvoicing(isTraining: Boolean = false): Bill {
        createShopInfo()
        val vat = createDefaultVatRates().first { it.id.value == "A" } // 0.1900
        val category = createCategory(name = "Speisen")
        val schnitzel =
            createProduct(
                categoryId = category.id.value,
                productName = "Wiener Schnitzel",
                productNumber = "INV_PRD001",
                price = BigDecimal("23.8000"),
            )
        val schorle =
            createProduct(
                categoryId = category.id.value,
                productName = "Apfelschorle",
                productNumber = "INV_PRD002",
                price = BigDecimal("4.5000"),
            )
        val tableWithSession = createTableWithSession(tableNumber = "INV1")

        return transaction {
            val session = tableWithSession.session
            val grossOne = BigDecimal("23.8000")
            val grossTwo = BigDecimal("4.5000")
            val total = grossOne.add(grossTwo)
            val net = total.divide(BigDecimal("1.19"), 4, RoundingMode.HALF_UP)

            val tx =
                TseTransaction.new {
                    this.tseSerialNumber = "TSE-TEST-0001"
                    this.transactionNumber = 4711L
                    this.processType = "Kassenbeleg-V1"
                    this.signature = "MEUCIQDtest"
                    this.signatureCounter = 99L
                    this.signatureAlgorithm = "ecdsa-plain-SHA256"
                    this.logTimeFormat = "unixTime"
                    this.startTime = currentTimeStamp()
                    this.endTime = currentTimeStamp()
                    this.tseTimestamp = 1_755_000_000L
                    this.tseStartTimestamp = 1_754_999_000L
                }

            val bill =
                Bill.new {
                    this.employeeId = session.employeeId
                    this.tableSession = session
                    this.billAmount = total
                    this.paidAmount = total
                    this.tips = BigDecimal.ZERO
                    this.startTime = session.startTime
                    this.endTime = currentTimeStamp()
                    this.billNumber = "RE-260816-7"
                    this.isTraining = isTraining
                    this.tseTransaction = tx
                }

            listOf(schnitzel to grossOne, schorle to grossTwo).forEach { (item, gross) ->
                OrderItem.new {
                    this.session = session
                    this.bill = bill
                    this.product = item
                    this.unitPrice = gross
                    this.finalPrice = gross
                    this.itemStatus = ItemStatus.PAID
                    this.vat = vat
                    this.takeAway = false
                }
            }

            BillVatDetail.new {
                this.bill = bill
                this.vat = vat
                this.netAmount = net
                this.vatAmount = total.subtract(net)
                this.totalAmount = total
            }

            bill
        }
    }
}
