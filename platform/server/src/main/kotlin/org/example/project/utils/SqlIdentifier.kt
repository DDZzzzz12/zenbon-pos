package org.example.project.utils

/**
 * Renders [name] as a PostgreSQL quoted identifier, doubling any embedded `"`.
 *
 * Use this wherever an identifier has to be interpolated into a statement because it cannot be a
 * bind parameter — table, column and sequence names in DDL. Identifiers read back from
 * `pg_catalog` are already trustworthy, but ones that originate outside the database are not: the
 * restore path takes column names from the CSV header inside the archive being restored, and any
 * name the current schema does not know reaches `ALTER TABLE ... ADD COLUMN "<name>" TEXT`
 * verbatim. Without doubling, a `"` there ends the identifier and the rest of the name is executed
 * as SQL.
 *
 * Quoting alone does not make an identifier *valid* — an over-long or empty name still fails — but
 * it does make it inert.
 */
fun quoteSqlIdentifier(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""
