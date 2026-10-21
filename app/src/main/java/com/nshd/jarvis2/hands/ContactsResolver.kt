package com.nshd.jarvis2.hands

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.nshd.jarvis2.memory.MemoryCategories
import com.nshd.jarvis2.memory.MemoryDb
import kotlin.math.abs
import kotlin.math.min

/** A resolved contact. [phone] is normalised to country-code digits. */
data class ResolvedContact(val displayName: String, val phone: String)

sealed class ContactResolution {
    data class Found(val contact: ResolvedContact) : ContactResolution()
    data class Ambiguous(val candidates: List<ResolvedContact>) : ContactResolution()
    object NotFound : ContactResolution()
}

/**
 * Contact resolution for calls and WhatsApp.
 *
 * Memory Core Contacts facts are checked first for aliases:
 *   "Mum = Amina"          (alias -> contact display name)
 *   "Mum = +91 98..."     (alias -> direct number)
 * Device contacts are then scored exact > starts-with > contains >
 * Levenshtein distance <= 2.
 */
class ContactsResolver(
    context: Context,
    private val memoryDb: MemoryDb? = null
) {
    private val appContext = context.applicationContext

    private data class ContactEntry(
        val displayName: String,
        val phone: String,
        val normalizedName: String
    )

    fun resolve(query: String): ContactResolution {
        val raw = query.trim()
        if (raw.isBlank()) return ContactResolution.NotFound

        val aliasHints = loadAliasHints()
        val normalizedQuery = normalizeName(raw)
        aliasHints.directPhones[normalizedQuery]?.let { phone ->
            return ContactResolution.Found(ResolvedContact(raw, phone))
        }

        if (looksLikePhone(raw)) {
            return ContactResolution.Found(ResolvedContact(raw, normalizePhone(raw)))
        }

        val effectiveName = aliasHints.nameAliases[normalizedQuery] ?: raw
        if (looksLikePhone(effectiveName)) {
            return ContactResolution.Found(ResolvedContact(raw, normalizePhone(effectiveName)))
        }

        val contacts = loadContacts()
        if (contacts.isEmpty()) return ContactResolution.NotFound
        val wanted = normalizeName(effectiveName)

        data class Scored(val entry: ContactEntry, val score: Int, val distance: Int)

        val scored = contacts.mapNotNull { entry ->
            val distance = levenshtein(wanted, entry.normalizedName)
            val score = when {
                entry.normalizedName == wanted -> 100
                entry.normalizedName.startsWith(wanted) -> 85
                wanted.startsWith(entry.normalizedName) && entry.normalizedName.length >= 3 -> 75
                entry.normalizedName.contains(wanted) -> 65
                distance <= 2 -> 50 - distance
                else -> -1
            }
            if (score >= 0) Scored(entry, score, distance) else null
        }
        if (scored.isEmpty()) return ContactResolution.NotFound

        val bestScore = scored.maxOf { it.score }
        val best = scored.filter { it.score == bestScore }
            .distinctBy { "${it.entry.normalizedName}|${it.entry.phone}" }
            .sortedBy { it.entry.displayName }
        return if (best.size == 1) {
            val entry = best.first().entry
            ContactResolution.Found(ResolvedContact(entry.displayName, entry.phone))
        } else {
            ContactResolution.Ambiguous(
                best.take(5).map { ResolvedContact(it.entry.displayName, it.entry.phone) }
            )
        }
    }

    /** True when [raw] normalises to a number stored in device contacts. */
    fun isNumberInContacts(raw: String): Boolean {
        val wanted = normalizePhone(raw)
        if (wanted.length < 10) return false
        return loadContacts().any { entry ->
            entry.phone == wanted || entry.phone.takeLast(10) == wanted.takeLast(10)
        }
    }

    private data class AliasHints(
        val nameAliases: Map<String, String>,
        val directPhones: Map<String, String>
    )

    private fun loadAliasHints(): AliasHints {
        val db = memoryDb ?: return AliasHints(emptyMap(), emptyMap())
        val names = mutableMapOf<String, String>()
        val phones = mutableMapOf<String, String>()
        try {
            db.allFacts(null, MemoryCategories.CONTACTS).forEach { fact ->
                val split = fact.text.indexOf('=')
                if (split <= 0) return@forEach
                val alias = normalizeName(fact.text.substring(0, split))
                val value = fact.text.substring(split + 1).trim()
                if (alias.isBlank() || value.isBlank()) return@forEach
                if (looksLikePhone(value)) phones[alias] = normalizePhone(value)
                else names[alias] = value
            }
        } catch (e: Exception) {
            // Alias hints are an optimisation; device contacts still resolve.
        }
        return AliasHints(names, phones)
    }

    private fun loadContacts(): List<ContactEntry> {
        if (ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) return emptyList()

        val entries = mutableListOf<ContactEntry>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        try {
            appContext.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex)?.trim().orEmpty() else ""
                    val number = if (numberIndex >= 0) cursor.getString(numberIndex)?.trim().orEmpty() else ""
                    if (name.isNotBlank() && number.isNotBlank()) {
                        entries += ContactEntry(name, normalizePhone(number), normalizeName(name))
                    }
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return entries.distinctBy { "${it.normalizedName}|${it.phone}" }
    }

    companion object {
        fun normalizePhone(raw: String): String {
            val trimmed = raw.trim()
            val digits = trimmed.filter { it.isDigit() }
            return when {
                digits.length == 10 -> "91$digits"
                digits.length == 11 && digits.startsWith("0") -> "91${digits.drop(1)}"
                else -> digits
            }
        }

        fun looksLikePhone(raw: String): Boolean {
            val allowed = raw.trim().filter { it.isDigit() || it == '+' || it == ' ' || it == '-' || it == '(' || it == ')' }
            if (allowed.length != raw.trim().length) return false
            val digits = raw.count { it.isDigit() }
            return digits in 10..15
        }

        fun normalizeName(raw: String): String = raw.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        fun levenshtein(a: String, b: String): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length
            // A very different length cannot reach the <=2 threshold used by callers.
            if (abs(a.length - b.length) > 2 && min(a.length, b.length) > 2) {
                // Still compute for short strings; long mismatches are not candidates.
                if (abs(a.length - b.length) > 2) return abs(a.length - b.length)
            }
            var previous = IntArray(b.length + 1) { it }
            var current = IntArray(b.length + 1)
            for (i in a.indices) {
                current[0] = i + 1
                for (j in b.indices) {
                    val cost = if (a[i] == b[j]) 0 else 1
                    current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + cost)
                }
                val swap = previous; previous = current; current = swap
            }
            return previous[b.length]
        }
    }
}
