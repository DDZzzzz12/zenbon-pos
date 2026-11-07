package org.example.project.core.data.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import org.example.project.database.PosDatabase

/**
 * Android SQLite database driver factory.
 *
 * Provides local SQLite database for caching, offline data, and UI state.
 * Business data is accessed through HTTP repositories that connect to
 * the desktop app's embedded server.
 */
actual class DatabaseDriverFactory(
    private val context: Context,
) {
    actual fun createDriver(): SqlDriver =
        AndroidSqliteDriver(
            schema = PosDatabase.Schema,
            context = context,
            name = DatabaseConfig.DATABASE_NAME,
            callback =
                object : AndroidSqliteDriver.Callback(PosDatabase.Schema) {
                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) {
                        // V1 -> V2: Add is_training column.
                        // Uses try-catch because intermediate builds may have already
                        // created the column via CREATE TABLE before the migration existed.
                        if (oldVersion < 2) {
                            try {
                                db.execSQL(
                                    "ALTER TABLE local_rtables ADD COLUMN is_training INTEGER NOT NULL DEFAULT 0",
                                )
                            } catch (_: Exception) {
                                // Column already exists — safe to ignore
                            }
                        }
                        // Future migrations (version 2+) use standard SQLDelight migrations
                        if (oldVersion >= 2 && newVersion > oldVersion) {
                            super.onUpgrade(db, oldVersion, newVersion)
                        }
                    }
                },
        )
}
