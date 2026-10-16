package com.dongfg.cardvault

import androidx.compose.ui.graphics.ImageBitmap

data class Card(val id: String, val name: String = "", val number: String = "", val note: String = "", val front: String? = null, val back: String? = null)

interface CardStore {
    suspend fun initialize()
    suspend fun load(): List<Card>
    suspend fun save(cards: List<Card>): List<Card>
    suspend fun pickImage(): String?
    suspend fun image(path: String): ImageBitmap
    fun newId(): String
    fun copy(text: String)
}

class MissingDatabaseException(val location: String) : Exception("找不到卡册数据库")
class CardStorageException(message: String) : Exception(message)

fun maskedNumber(number: String): String {
    val compact = number.filterNot(Char::isWhitespace)
    return if (compact.isEmpty()) "" else "••••  ${compact.takeLast(minOf(4, compact.length - 1).coerceAtLeast(0))}"
}

fun Card.matches(query: String): Boolean {
    val term = query.trim()
    return name.contains(term, true) || note.contains(term, true) || number.filterNot(Char::isWhitespace).contains(term.filterNot(Char::isWhitespace), true)
}

class CloseController { var request: () -> Unit = {} }
