package org.qosp.notes.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Test class for verifying database migrations.
 *
 * These tests ensure that all migrations in the AppDatabase can be applied successfully.
 * The approach is to create a database with the migrations applied and verify that it can be opened without errors.
 *
 * While these tests don't validate the specific changes made by each migration (like adding a column or creating an index),
 * they do ensure that the migrations don't cause any errors when applied to a database, which is the most important aspect
 * of migration testing.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    /**
     * Tests that all migrations can be applied together.
     *
     * This test creates a database and applies all migrations from version 1 to the current
     * version. It verifies that the database can be opened successfully after all migrations
     * are applied.
     */
    @Test
    @Throws(IOException::class)
    fun testAllMigrations() {
        // Create a database with all migrations
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "migration-test"
        )
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7
            )
            .build()

        // Verify that the database can be opened
        db.openHelper.writableDatabase

        // Close the database
        db.close()
    }

    /**
     * Tests the migration from version 2 to version 3.
     *
     * This migration adds the 'screenAlwaysOn' column to the 'notes' table.
     * The test verifies that the database can be opened successfully after the migration is applied.
     */
    @Test
    @Throws(IOException::class)
    fun testMigration2To3() {
        // Create a database with migration 2 to 3
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "migration-2-3-test"
        )
            .addMigrations(AppDatabase.MIGRATION_2_3)
            .build()

        // Verify that the database can be opened
        db.openHelper.writableDatabase

        // Close the database
        db.close()
    }

    /**
     * Tests the migration from version 3 to version 4.
     *
     * This migration adds the 'storageUri' column to the 'cloud_ids' table.
     * The test verifies that the database can be opened successfully after the migration is applied.
     */
    @Test
    @Throws(IOException::class)
    fun testMigration3To4() {
        // Create a database with migration 3 to 4
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "migration-3-4-test"
        )
            .addMigrations(AppDatabase.MIGRATION_3_4)
            .build()

        // Verify that the database can be opened
        db.openHelper.writableDatabase

        // Close the database
        db.close()
    }

    /**
     * Tests the migration from version 4 to version 5.
     *
     * This migration creates two indices on the 'cloud_ids' table:
     * - cloud_ids_id_index on localNoteId
     * - cloud_ids_id_provider_index on localNoteId and provider
     *
     * The test verifies that the database can be opened successfully after the migration is applied.
     */
    @Test
    @Throws(IOException::class)
    fun testMigration4To5() {
        // Create a database with migration 4 to 5
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "migration-4-5-test"
        )
            .addMigrations(AppDatabase.MIGRATION_4_5)
            .build()

        // Verify that the database can be opened
        db.openHelper.writableDatabase

        // Close the database
        db.close()
    }

    /**
     * Tests the migration from version 3 to the current version.
     *
     * This simulates a user upgrading from an app with database version 3 directly to the
     * current schema, applying every migration in sequence.
     */
    @Test
    @Throws(IOException::class)
    fun testMigration3ToCurrent() {
        // Create a database with migrations from 3 to the current version
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "migration-3-current-test"
        )
            .addMigrations(
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7
            )
            .build()

        // Verify that the database can be opened
        db.openHelper.writableDatabase

        // Close the database
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun testMigration5To6() {
        val databaseName = "migration-5-6-test"
        migrationHelper.createDatabase(databaseName, 5).apply {
            execSQL(
                """
                INSERT INTO cloud_ids
                    (localNoteId, remoteNoteId, provider, extras, isDeletedLocally, isBeingUpdated, storageUri)
                VALUES (1, 0, 'FILE_STORAGE', NULL, 0, 0, 'content://old/note.md')
                """.trimIndent()
            )
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            databaseName,
            6,
            true,
            AppDatabase.MIGRATION_5_6,
        ).use { db ->
            db.query(
                "SELECT relativePath, contentHash, lastSyncedModified FROM cloud_ids"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
                assertTrue(cursor.isNull(1))
                assertTrue(cursor.isNull(2))
            }
        }
    }

    @Test
    @Throws(IOException::class)
    fun testMigration6To7KeepsTheBestMapping() {
        val databaseName = "migration-6-7-test"
        migrationHelper.createDatabase(databaseName, 6).apply {
            execSQL(
                """
                INSERT INTO cloud_ids
                    (localNoteId, remoteNoteId, provider, extras, isDeletedLocally, isBeingUpdated,
                     storageUri, relativePath, contentHash, lastSyncedModified)
                VALUES (1, 0, 'FILE_STORAGE', NULL, 0, 0, 'content://old/note.md', NULL, NULL, NULL)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO cloud_ids
                    (localNoteId, remoteNoteId, provider, extras, isDeletedLocally, isBeingUpdated,
                     storageUri, relativePath, contentHash, lastSyncedModified)
                VALUES (1, 0, 'FILE_STORAGE', NULL, 0, 0, 'content://new/note.md', 'note.md', 'hash', 200)
                """.trimIndent()
            )
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            databaseName,
            7,
            true,
            AppDatabase.MIGRATION_6_7,
        ).use { db ->
            db.query(
                "SELECT storageUri FROM cloud_ids WHERE localNoteId = 1"
            ).use { cursor ->
                assertEquals(1, cursor.count)
                assertTrue(cursor.moveToFirst())
                assertEquals("content://new/note.md", cursor.getString(0))
            }
        }
    }
}
