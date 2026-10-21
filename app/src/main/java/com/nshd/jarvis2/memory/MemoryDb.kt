package com.nshd.jarvis2.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Fact(
    val id: Long,
    val category: String,
    val text: String,
    val created: Long
)

data class StoredMessage(
    val id: Long,
    val role: String,
    val content: String,
    val created: Long
)

object MemoryCategories {
    const val GENERAL = "General"
    const val PERSONAL = "Personal"
    const val PREFERENCES = "Preferences"
    const val CONTACTS = "Contacts"
    const val ROUTINES = "Routines"

    /** Categories shown as filter chips (+ "All" is added by the UI). */
    val FILTERABLE = listOf(PERSONAL, PREFERENCES, CONTACTS, ROUTINES)

    /** Everything a fact can be saved under (General is the auto-save default). */
    val ALL = listOf(GENERAL, PERSONAL, PREFERENCES, CONTACTS, ROUTINES)
}

/**
 * Memory Core storage — plain SQLiteOpenHelper (deliberately no Room/KSP,
 * so the CI build stays plugin-light). Two tables: durable [facts] and the
 * chat history. Call the DAO methods from a background dispatcher.
 */
class MemoryDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE facts (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "category TEXT NOT NULL, " +
                "text TEXT NOT NULL, " +
                "created INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE chat_messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "role TEXT NOT NULL, " +
                "content TEXT NOT NULL, " +
                "created INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 — nothing to migrate yet.
    }

    // ── Facts ──────────────────────────────────────────────────────────

    fun addFact(category: String, text: String): Long {
        val values = ContentValues().apply {
            put("category", category)
            put("text", text)
            put("created", System.currentTimeMillis())
        }
        return writableDatabase.insert("facts", null, values)
    }

    fun updateFact(id: Long, category: String, text: String) {
        val values = ContentValues().apply {
            put("category", category)
            put("text", text)
        }
        writableDatabase.update("facts", values, "id = ?", arrayOf(id.toString()))
    }

    fun deleteFact(id: Long) {
        writableDatabase.delete("facts", "id = ?", arrayOf(id.toString()))
    }

    fun allFacts(query: String?, category: String?): List<Fact> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (!query.isNullOrBlank()) {
            clauses += "text LIKE ?"
            args += "%${query.trim()}%"
        }
        if (!category.isNullOrBlank()) {
            clauses += "category = ?"
            args += category
        }
        val where = if (clauses.isEmpty()) null else clauses.joinToString(" AND ")
        val result = mutableListOf<Fact>()
        readableDatabase.query(
            "facts", arrayOf("id", "category", "text", "created"),
            where, args.toTypedArray(), null, null, "id DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += Fact(
                    id = cursor.getLong(0),
                    category = cursor.getString(1),
                    text = cursor.getString(2),
                    created = cursor.getLong(3)
                )
            }
        }
        return result
    }

    fun factCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM facts", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    // ── Chat history ───────────────────────────────────────────────────

    fun addMessage(role: String, content: String): Long {
        val values = ContentValues().apply {
            put("role", role)
            put("content", content)
            put("created", System.currentTimeMillis())
        }
        return writableDatabase.insert("chat_messages", null, values)
    }

    /** Most recent [limit] messages, in chronological order. */
    fun lastMessages(limit: Int): List<StoredMessage> {
        val result = mutableListOf<StoredMessage>()
        readableDatabase.query(
            "chat_messages", arrayOf("id", "role", "content", "created"),
            null, null, null, null, "id DESC", limit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += StoredMessage(
                    id = cursor.getLong(0),
                    role = cursor.getString(1),
                    content = cursor.getString(2),
                    created = cursor.getLong(3)
                )
            }
        }
        return result.asReversed()
    }

    fun clearChat() {
        writableDatabase.delete("chat_messages", null, null)
    }

    companion object {
        private const val DB_NAME = "jarvis_memory.db"
        private const val DB_VERSION = 1
    }
}
