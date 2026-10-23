package com.nshd.jarvis2.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Fact(
    val id: Long,
    val category: String,
    val text: String,
    val created: Long,
    /** v1.9.0 (#73): auto-extracted facts wait in the review queue. */
    val pending: Boolean = false
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

    /** v1.9.0 (#109): quick notes ("note that …"). */
    const val NOTES = "Notes"

    /** Categories shown as filter chips (+ "All" is added by the UI). */
    val FILTERABLE = listOf(PERSONAL, PREFERENCES, CONTACTS, ROUTINES, NOTES)

    /** Everything a fact can be saved under (General is the auto-save default). */
    val ALL = listOf(GENERAL, PERSONAL, PREFERENCES, CONTACTS, ROUTINES, NOTES)
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
                "created INTEGER NOT NULL, " +
                "pending INTEGER NOT NULL DEFAULT 0)"
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
        // v2 (#73): facts gain the review-queue flag.
        if (oldVersion < 2) {
            db.execSQL(
                "ALTER TABLE facts ADD COLUMN pending INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    // ── Facts ──────────────────────────────────────────────────────────

    fun addFact(category: String, text: String): Long =
        addFact(category, text, pending = false)

    /** v1.9.0 (#73): an auto-extracted fact lands in the review queue. */
    fun addPendingFact(category: String, text: String): Long =
        addFact(category, text, pending = true)

    private fun addFact(category: String, text: String, pending: Boolean): Long {
        val values = ContentValues().apply {
            put("category", category)
            put("text", text)
            put("created", System.currentTimeMillis())
            put("pending", if (pending) 1 else 0)
        }
        return writableDatabase.insert("facts", null, values)
    }

    /** v1.9.0 (#73): approve (pending=false) or un-approve one fact. */
    fun setFactPending(id: Long, pending: Boolean) {
        val values = ContentValues().apply {
            put("pending", if (pending) 1 else 0)
        }
        writableDatabase.update("facts", values, "id = ?", arrayOf(id.toString()))
    }

    /** v1.9.0 (#73): the review queue — auto-extracted, not yet approved. */
    fun pendingFacts(): List<Fact> = queryFacts(
        where = "pending = 1", args = emptyArray()
    )

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
        // #73: the review queue stays OUT of every normal fact read
        // (prompt injection, lists) until the user approves it.
        clauses += "pending = 0"
        if (!query.isNullOrBlank()) {
            clauses += "text LIKE ?"
            args += "%${query.trim()}%"
        }
        if (!category.isNullOrBlank()) {
            clauses += "category = ?"
            args += category
        }
        return queryFacts(clauses.joinToString(" AND "), args.toTypedArray())
    }

    private fun queryFacts(where: String?, args: Array<String>): List<Fact> {
        val result = mutableListOf<Fact>()
        readableDatabase.query(
            "facts", arrayOf("id", "category", "text", "created", "pending"),
            where, args, null, null, "id DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += Fact(
                    id = cursor.getLong(0),
                    category = cursor.getString(1),
                    text = cursor.getString(2),
                    created = cursor.getLong(3),
                    pending = cursor.getInt(4) != 0
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
        val id = writableDatabase.insert("chat_messages", null, values)
        // F6 audit (data safety): this table previously grew without
        // bound — every chat and voice turn appends rows forever and
        // nothing ever deleted. Keep the newest MAX_HISTORY_MESSAGES
        // rows; prune cheaply (one indexed delete every ~100 inserts).
        // The pipeline only ever reads the last ≤16, so 1000 is a
        // generous history window.
        if (id > 0 && id % 100L == 0L) pruneHistory()
        return id
    }

    private fun pruneHistory() {
        try {
            writableDatabase.execSQL(
                "DELETE FROM chat_messages WHERE id NOT IN " +
                    "(SELECT id FROM chat_messages ORDER BY id DESC " +
                    "LIMIT $MAX_HISTORY_MESSAGES)"
            )
        } catch (e: Exception) {
            // Pruning must never break message writes.
        }
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

    /** #66: delete one stored message by id (edit-and-resend truncation). */
    fun deleteMessage(id: Long) {
        writableDatabase.delete("chat_messages", "id = ?", arrayOf(id.toString()))
    }

    /** v1.9.0 (#82): total stored chat rows (auto-compact trigger). */
    fun messageCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM chat_messages", null)
            .use { cursor ->
                return if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
    }

    /** v1.9.0 (#82): the OLDEST [limit] messages, chronological order. */
    fun oldestMessages(limit: Int): List<StoredMessage> {
        val result = mutableListOf<StoredMessage>()
        readableDatabase.query(
            "chat_messages", arrayOf("id", "role", "content", "created"),
            null, null, null, null, "id ASC", limit.toString()
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
        return result
    }

    /** v1.9.0 (#82): delete a batch of messages by id (post-compact). */
    fun deleteMessages(ids: List<Long>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { id ->
                db.delete("chat_messages", "id = ?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clearChat() {
        writableDatabase.delete("chat_messages", null, null)
    }

    companion object {
        private const val DB_NAME = "jarvis_memory.db"
        private const val DB_VERSION = 2

        /** F6: chat history retention cap (see pruneHistory). */
        private const val MAX_HISTORY_MESSAGES = 1000
    }
}
