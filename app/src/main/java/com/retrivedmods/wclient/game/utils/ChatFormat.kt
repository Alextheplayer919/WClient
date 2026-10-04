package com.retrivedmods.wclient.game.utils

import kotlin.random.Random

/** Shared formatting for ordinary chat and module-generated announcements. */
object ChatFormat {
    private const val CHARACTERS = "abcdefghjklmnopqrstuvwxyz0123456789"
    private val RANDOM_RANGE = 12..22

    fun randomString(): String = buildString {
        repeat(Random.nextInt(RANDOM_RANGE.first, RANDOM_RANGE.last + 1)) {
            append(CHARACTERS[Random.nextInt(CHARACTERS.length)])
        }
    }

    fun format(message: String, prefix: String = "", green: Boolean = false, random: Boolean = true): String {
        val trimmedMessage = message.trimStart()
        val body = if (green) trimmedMessage.removePrefix("> ").removePrefix(">") else trimmedMessage
        val text = listOf(prefix.trim().replace("\n", " ").replace("\r", " "), body)
            .filter { it.isNotEmpty() }.joinToString(" ")
        return (if (green) "> " else "") + text + (if (random) " | ${randomString()}" else "")
    }
}
