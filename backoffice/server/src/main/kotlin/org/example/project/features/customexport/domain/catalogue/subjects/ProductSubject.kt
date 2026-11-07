package org.example.project.features.customexport.domain.catalogue.subjects

import org.example.project.core.domain.enums.auth.Capability
import org.example.project.features.customexport.domain.catalogue.ExportSubject
import org.example.project.features.product.database.tables.ProductTable

/**
 * Produkte — the article list, which is what an operator hands a new colleague, a price-checking
 * advisor or their own spreadsheet when they want to see the whole menu at once.
 *
 * The root is [ProductTable]; the Warengruppe arrives on its own, because `product.category_id` is a
 * foreign key and the graph reads foreign keys. That is the point of registering the category table
 * rather than the id column: the file says "Getränke", not "17".
 *
 * ## No date column, and that is the schema's doing
 *
 * `product` has **no `created_at`** — only `updated_at`, which moves every time somebody corrects a
 * price. Declaring that as [ExportSubject.dateColumn] would answer "articles edited in this Zeitraum"
 * to an operator who asked "articles added in this Zeitraum", and the file gives no hint which
 * question it answered. So the subject declares none, `CustomExportServiceImpl` refuses to store a
 * date range against it, and the column is still offered as an ordinary tickable column under a
 * heading that says what it is. The right fix is a `created_at` on the table, not a filter pointed at
 * the wrong column.
 *
 * ## No shop column
 *
 * `product` carries no shop of its own — the menu is not per-shop data in this schema. Stated here
 * rather than left to be noticed, as [IncomingInvoiceSubject] states it for the AP chain: a null
 * [ExportSubject.shopColumn] must always be a decision somebody wrote down, never an omission.
 *
 * Every field is gated by [Capability.CATALOG_READ], which is what `CatalogRules` requires of
 * `GET /api/products`: an export must never be an easier way to read data than the screen showing it.
 */
object ProductSubject {
    val subject: ExportSubject =
        ExportSubject(
            key = "produkt",
            label = "Produkte",
            root = ProductTable,
            dateColumn = null,
            shopColumn = null,
        )
}
