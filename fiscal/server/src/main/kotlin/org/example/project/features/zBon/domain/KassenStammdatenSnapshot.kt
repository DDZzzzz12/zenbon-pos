package org.example.project.features.zBon.domain

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.example.project.BuildConfig
import org.example.project.core.domain.dto.setup.ShopConfigurationDto
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.features.setup.database.tables.ShopConfigurationTable
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.zBon.database.tables.ZBonStammTerminalTable
import org.example.project.features.zBon.database.tables.ZBonTable
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.features.zBon.domain.models.ZBonStammTerminal
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.slf4j.LoggerFactory

/**
 * The single definition of "the CASH REGISTER Stammdaten belonging to a Kassenabschluss" —
 * DSFinV-K Tz. 3.2 (p.23): „Zur Vermeidung von Redundanzen werden die Stammdaten für jeden
 * Kassenabschluss …". The Stammdaten are a property of the **closing**, not of the current
 * configuration (audit round-7 C3).
 *
 * [StammdatenSnapshot] is the sibling for the SHOP identity (`cashpointclosing.csv`,
 * `location.csv`, round-5 KABS-2). This object covers the two files that round-5 stopped short of:
 *
 *  * `cashregister.csv` (Stamm_Kassen) — sourced from the `KASSEN_MELDUNG_CONFIG` setting, whose
 *    `kasseSwVersion` [KassenSwVersionSync] **rewrites on every server start and after a restore**, and
 *  * `slaves.csv` (Stamm_Terminals) — sourced from the live `terminals` table.
 *
 * Both therefore changed under a closed period: re-exporting an already-delivered Kassenabschluss
 * produced different bytes than what was handed over, breaking closing immutability.
 *
 * ## What is snapshotted, and what deliberately is not
 *
 * Only the genuinely per-closing master data is captured: `KASSE_BRAND`, `KASSE_MODELL`,
 * `KASSE_SW_BRAND`, `KASSE_SW_VERSION`, and per terminal `TERMINAL_BRAND`, `TERMINAL_MODELL`,
 * `TERMINAL_SERIENNR`, `TERMINAL_SW_BRAND`, `TERMINAL_SW_VERSION`.
 *
 * **`Z_KASSE_ID`, `Z_NR`, `Z_ERSTELLUNG` and `TERMINAL_ID` are JOIN KEYS and must NOT be
 * snapshotted.** `Z_KASSE_ID` is a primary-key component of every DSFinV-K file, and this
 * implementation derives it from `kasseSeriennr` — the very value `KASSE_SERIENNR` carries. Freezing
 * `KASSE_SERIENNR` per closing while `Z_KASSE_ID` kept reading the live config would desynchronise
 * the two and break every cross-file join, trading a byte-stability defect for a structural one.
 * `KASSE_BASISWAEH_CODE` is a literal (`"EUR"`) with no live source to drift, and
 * `KEINE_UST_ZUORDNUNG` is already derived from the closing's own rows (audit STAM-3/7).
 *
 * ## Resolution lives here, not at the emit site
 *
 * [resolveLive] holds the `ifBlank { … }` defaults the export used to apply inline. The snapshot
 * stores the **resolved** values, so a snapshotted closing and a fallback closing with the same
 * data emit identical bytes — the same reason [StammdatenSnapshot] stores the PLZ already padded.
 * It also pins the `BuildConfig` fallbacks, which would otherwise drift with the running binary
 * just like the config value they stand in for.
 */
object KassenStammdatenSnapshot {
    private val logger = LoggerFactory.getLogger(KassenStammdatenSnapshot::class.java)

    private val json = Json { ignoreUnknownKeys = true }

    /** Stamm_Kassen values in export form — every `ifBlank` default already applied. */
    data class ResolvedKasse(
        val brand: String,
        val modell: String,
        val swBrand: String,
        val swVersion: String,
    )

    /** One Stamm_Terminals row in export form. */
    data class ResolvedTerminal(
        val terminalId: String,
        val brand: String?,
        val modell: String?,
        val seriennr: String?,
        val swBrand: String,
        val swVersion: String?,
    )

    /**
     * The live resolution: the configured Kassenmeldung values, with the historical `ifBlank`
     * fallbacks. **One definition** — used both when writing a snapshot and when a pre-snapshot
     * closing falls back, so the two cannot produce different bytes for the same data.
     */
    fun resolveLive(config: ShopConfigurationDto.KassenMeldungConfigDto) =
        ResolvedKasse(
            brand = config.kasseBrand.ifBlank { BuildConfig.APP_NAME },
            modell = config.kasseModell.ifBlank { "Desktop POS" },
            swBrand = config.kasseSwBrand.ifBlank { BuildConfig.APP_NAME },
            swVersion = config.kasseSwVersion.ifBlank { BuildConfig.APP_VERSION },
        )

    /**
     * Resolves the Stamm_Kassen values for [zBon]: its own snapshot when present, else the live
     * [config].
     *
     * The fallback is load-bearing, not a convenience — see [StammdatenSnapshot] for the full
     * argument. NULL is the honest representation for a closing taken before this fix, and it
     * renders exactly the bytes that closing already exported.
     */
    fun resolveFor(
        zBon: ZBon,
        config: ShopConfigurationDto.KassenMeldungConfigDto,
    ): ResolvedKasse {
        if (zBon.stammKasseSnapshotAt == null) return resolveLive(config)
        val live = resolveLive(config)
        return ResolvedKasse(
            brand = zBon.stammKasseBrand ?: live.brand,
            modell = zBon.stammKasseModell ?: live.modell,
            swBrand = zBon.stammKasseSwBrand ?: live.swBrand,
            swVersion = zBon.stammKasseSwVersion ?: live.swVersion,
        )
    }

    /**
     * Resolves the Stamm_Terminals rows for [zBon]: the terminals captured at closing time when a
     * snapshot exists — **including the empty set** — else the live `terminals` table.
     *
     * Requires an ambient Exposed transaction.
     */
    fun terminalsFor(zBon: ZBon): List<ResolvedTerminal> {
        if (zBon.stammKasseSnapshotAt == null) return liveTerminals()
        return zBon.stammTerminals
            .sortedBy { it.id.value }
            .map {
                ResolvedTerminal(
                    terminalId = it.terminalId,
                    brand = it.terminalBrand,
                    modell = it.terminalModell,
                    seriennr = it.terminalSeriennr,
                    swBrand = it.terminalSwBrand,
                    swVersion = it.terminalSwVersion,
                )
            }
    }

    /**
     * Copies the CURRENT cash-register Stammdaten onto [zBon] — the Kassenmeldung config plus the
     * terminals that exist right now.
     *
     * Requires an ambient Exposed transaction. Callers are responsible for having established that
     * [zBon] carries no snapshot yet; existing child rows are cleared so a re-application cannot
     * duplicate them.
     */
    fun applyTo(
        zBon: ZBon,
        shopId: Long,
    ) {
        val resolved = resolveLive(loadKassenConfig(shopId))
        zBon.stammKasseBrand = resolved.brand
        zBon.stammKasseModell = resolved.modell
        zBon.stammKasseSwBrand = resolved.swBrand
        zBon.stammKasseSwVersion = resolved.swVersion

        ZBonStammTerminalTable.deleteWhere { ZBonStammTerminalTable.zBonId eq zBon.id }
        liveTerminals().forEach { t ->
            ZBonStammTerminal.new {
                this.zBon = zBon
                this.terminalId = t.terminalId
                this.terminalBrand = t.brand
                this.terminalModell = t.modell
                this.terminalSeriennr = t.seriennr
                this.terminalSwBrand = t.swBrand
                this.terminalSwVersion = t.swVersion
            }
        }

        // Set LAST: it is the all-or-nothing discriminator for the whole group, so it must not be
        // observable before the values it vouches for exist (finding D3 — no "partially
        // snapshotted" state). Written per row, never as a column default (KABS-1).
        zBon.stammKasseSnapshotAt = currentTimeStamp()
    }

    /**
     * Freeze-on-rewrite. Stamps the CURRENT — i.e. **pre-rewrite** — cash-register Stammdaten onto
     * every closing that still carries no snapshot, and returns how many were frozen.
     *
     * Requires an ambient Exposed transaction, and **must be called BEFORE
     * [KassenSwVersionSync] writes [newSwVersion]**.
     *
     * Why this exists: closings taken before round-7 C3 have no snapshot and render through the
     * live-row fallback — which is exactly the bytes they have been exporting all along. The
     * startup rewrite is the last moment at which those bytes are still knowable, so it is the
     * moment to preserve them. Without it, the first upgrade after this fix would still silently
     * change the `KASSE_SW_VERSION` of every historical closing — the defect would be fixed only
     * for closings taken from here on, and the existing ones would take one final hit. This mirrors
     * the round-6 C5 freeze-on-edit in `ShopInfoServiceImpl.updateShopInfo` exactly.
     *
     * The terminals are frozen in the same call rather than left NULL, so the group stays
     * all-or-nothing (D3) — and by the same argument: the live terminal set is what those closings
     * currently emit, and preserving today's bytes is the whole point.
     *
     * **No version change means no freeze.** Nothing is about to be lost, so stamping a snapshot
     * would convert an honest NULL into a recorded claim for no reason.
     *
     * **A non-null snapshot is never touched** — overwriting one would rewrite fiscal data that may
     * already have been handed to a Prüfer.
     *
     * A shop with no `KASSEN_MELDUNG_CONFIG` row at all is skipped, and that is equivalent to
     * running: with no config the resolution falls through to `BuildConfig.APP_VERSION`, i.e. to
     * [newSwVersion] itself, so there is no old value to preserve.
     */
    fun freezeUnsnapshottedClosings(newSwVersion: String): Int {
        var frozen = 0
        ShopConfigurationTable
            .selectAll()
            .where {
                (ShopConfigurationTable.key eq ConfigKey.KASSEN_MELDUNG_CONFIG.key)
            }.map { it[ShopConfigurationTable.shopId].value }
            .distinct()
            .forEach { shopId ->
                val current = loadKassenConfig(shopId)
                if (resolveLive(current).swVersion == newSwVersion) return@forEach

                val pending =
                    ZBon
                        .find {
                            (ZBonTable.shopInfoId eq shopId) and ZBonTable.stammKasseSnapshotAt.isNull()
                        }.toList()
                pending.forEach { applyTo(it, shopId) }
                frozen += pending.size
            }
        if (frozen > 0) {
            logger.info(
                "KassenStammdatenSnapshot: froze the pre-upgrade Stamm_Kassen/Stamm_Terminals onto " +
                    "{} closing(s) before rewriting KASSE_SW_VERSION to {}",
                frozen,
                newSwVersion,
            )
        }
        return frozen
    }

    /**
     * Freeze-on-rewrite for the TERMINAL side. Stamps the CURRENT — i.e. **pre-rewrite** —
     * cash-register Stammdaten onto every closing that still carries no snapshot, and returns how
     * many were frozen.
     *
     * Requires an ambient Exposed transaction, and **must be called BEFORE a terminal's master data
     * is written** (`TerminalServiceImpl.recordReportedSwVersion`).
     *
     * The sibling of [freezeUnsnapshottedClosings] for the other live source: a closing without a
     * snapshot renders `slaves.csv` from the live `terminals` table ([terminalsFor]), so moving a
     * terminal's `terminal_sw_version` would change the bytes of every such closing — in every
     * shop, because [liveTerminals] is not filtered by shop. After a regular upgrade the server's
     * startup freeze has normally run before any till reports its new version, which makes this a
     * no-op; it exists so closing immutability does not depend on that ordering.
     *
     * The caller invokes it only when a value is actually about to change — the same "no change, no
     * freeze" rule. **A non-null snapshot is never touched.**
     */
    fun freezeUnsnapshottedClosingsBeforeTerminalRewrite(): Int {
        val pending = ZBon.find { ZBonTable.stammKasseSnapshotAt.isNull() }.toList()
        pending.forEach { applyTo(it, it.shopInfo.id.value) }
        if (pending.isNotEmpty()) {
            logger.info(
                "KassenStammdatenSnapshot: froze the pre-rewrite Stamm_Kassen/Stamm_Terminals onto " +
                    "{} closing(s) before rewriting terminal master data",
                pending.size,
            )
        }
        return pending.size
    }

    /**
     * Reads `KASSEN_MELDUNG_CONFIG` in the ambient transaction.
     *
     * Mirrors `ConfigurationRepository.getSettingAsString` (JSONB → string) without needing its
     * suspend surface, so the non-suspend `ZBonRepositoryImpl.createReport` can snapshot inline.
     * An absent or undecodable row yields the defaults — the same register-sanctioned fallback the
     * export applies (round-3 STAM-5); this path only decides which values get FROZEN, and the
     * export's own hard failure on an undecodable config still stands.
     */
    private fun loadKassenConfig(shopId: Long): ShopConfigurationDto.KassenMeldungConfigDto {
        val raw =
            ShopConfigurationTable
                .selectAll()
                .where {
                    (ShopConfigurationTable.shopId eq shopId) and
                        (ShopConfigurationTable.key eq ConfigKey.KASSEN_MELDUNG_CONFIG.key)
                }.singleOrNull()
                ?.get(ShopConfigurationTable.value)
                ?.let { element ->
                    when (element) {
                        is JsonPrimitive -> element.contentOrNull ?: element.toString()
                        is JsonObject, is JsonArray -> element.toString()
                    }
                }
                ?: return ShopConfigurationDto.KassenMeldungConfigDto()

        return try {
            json.decodeFromString<ShopConfigurationDto.KassenMeldungConfigDto>(raw)
        } catch (e: SerializationException) {
            logger.warn("KassenStammdatenSnapshot: undecodable KASSEN_MELDUNG_CONFIG for shopId={}", shopId, e)
            ShopConfigurationDto.KassenMeldungConfigDto()
        }
    }

    /**
     * The live terminal set, read exactly as the export used to read it (`Terminal.all()` —
     * unfiltered by `isActive` and by shop, see the note in `DsfinvkExportServiceImpl`). Kept
     * identical on purpose: the snapshot must capture what the fallback would have emitted, or
     * introducing it would itself change the bytes.
     */
    private fun liveTerminals(): List<ResolvedTerminal> =
        Terminal.all().sortedBy { it.id.value }.map {
            ResolvedTerminal(
                terminalId = it.clientId,
                brand = it.terminalBrand,
                modell = it.terminalModel,
                seriennr = it.terminalSerialNr,
                swBrand = it.terminalSwBrand,
                swVersion = it.terminalSwVersion,
            )
        }
}
