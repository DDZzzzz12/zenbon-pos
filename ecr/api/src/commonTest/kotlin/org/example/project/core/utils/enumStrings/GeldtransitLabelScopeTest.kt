package org.example.project.core.utils.enumStrings

import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Geldtransit entry types must not read as bank-only.
 *
 * DSFinV-K 2.4 Anhang C p. 58 defines `Geldtransit` as the withdrawal/deposit of cash
 * „um es z. B. zur Bank **oder in einen Tresor** zu bringen. Zusätzlich dient der
 * Geschäftsvorfall der Darstellung von Bargeldverschiebungen zwischen einzelnen Kassen."
 *
 * The labels used to say only „Bank-Auszahlung" / „Bank-Einzahlung", with descriptions
 * naming the Bankkonto explicitly. An operator sweeping the Tageseinnahme into the safe
 * found no matching option and would reasonably pick Barentnahme instead — which maps to
 * GV_TYP `Auszahlung`, not `Geldtransit` (`KassenbuchDsfinvkMapping.getGvTyp`), and to
 * counter-account 9000 instead of 1460. A wrong GV_TYP in an immutable fiscal export,
 * caused by a string.
 *
 * These assertions pin the intent, not the wording: the label must reach beyond the bank.
 */
class GeldtransitLabelScopeTest {
    private val transitTypes =
        listOf(
            KassenbuchEntryType.CASH_TRANSFER_IN,
            KassenbuchEntryType.CASH_TRANSFER_OUT,
        )

    @BeforeTest
    fun initializeTranslations() {
        // `getName` resolves through TranslationRegistry, which every UI module initializes
        // once at startup (see composeApp Language.kt and adminApp AdminTranslations.kt).
        TranslationRegistry.initialize(defaultTranslations())
    }

    @Test
    fun germanTransitLabelsNameTheTresor() {
        for (type in transitTypes) {
            val label = type.getName(Language.GERMAN) + " " + type.getDescription(Language.GERMAN)
            assertTrue(
                label.contains("Tresor", ignoreCase = true),
                "$type German label must mention the Tresor, was: $label",
            )
        }
    }

    @Test
    fun englishTransitLabelsNameTheSafe() {
        for (type in transitTypes) {
            val label = type.getName(Language.ENGLISH) + " " + type.getDescription(Language.ENGLISH)
            assertTrue(
                label.contains("safe", ignoreCase = true),
                "$type English label must mention the safe, was: $label",
            )
        }
    }

    @Test
    fun chineseTransitLabelsNameTheSafe() {
        for (type in transitTypes) {
            val label = type.getName(Language.CHINESE) + " " + type.getDescription(Language.CHINESE)
            assertTrue(
                label.contains("保险柜"),
                "$type Chinese label must mention the safe (保险柜), was: $label",
            )
        }
    }

    @Test
    fun transitLabelsAlsoCoverRegisterToRegisterMoves() {
        // Anhang C p. 58, second sentence: Geldtransit also carries Bargeldverschiebungen
        // zwischen einzelnen Kassen. Without this the multi-till case has no visible home.
        //
        // Matches "andere Kasse" rather than bare "Kasse" on purpose — the old text already
        // said "Kassenbestands", so a bare substring check passed while the label was still
        // bank-only.
        for (type in transitTypes) {
            val label = type.getName(Language.GERMAN) + " " + type.getDescription(Language.GERMAN)
            assertTrue(
                label.contains("andere", ignoreCase = true) &&
                    label.contains("Kasse", ignoreCase = true),
                "$type German label must cover moves to/from another Kasse, was: $label",
            )
        }
    }

    @Test
    fun changeFundIsNotDescribedAsAnIncomingDeposit() {
        // CASH_DEPOSIT maps to GV_TYP `Einzahlung`, the catch-all for inflows the standard
        // fields cannot express (Anhang C p. 58). Cash arriving from the safe is a
        // Geldtransit, so this description must not advertise itself as the Tresor route.
        val german = KassenbuchEntryType.CASH_DEPOSIT.getDescription(Language.GERMAN)
        assertTrue(
            !german.contains("Tresor", ignoreCase = true),
            "CASH_DEPOSIT (GV_TYP Einzahlung) must not steer Tresor movements away from " +
                "Geldtransit, was: $german",
        )
    }
}
