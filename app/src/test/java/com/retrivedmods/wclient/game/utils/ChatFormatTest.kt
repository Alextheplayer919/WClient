package com.retrivedmods.wclient.game.utils

import org.junit.Assert.*
import org.junit.Test

class ChatFormatTest {
    @Test fun greenChatUsesLiteralGreaterThan() {
        assertEquals("> there goes your totem", ChatFormat.format("there goes your totem", green = true, random = false))
        assertEquals("> E hello", ChatFormat.format("> hello", "E", green = true, random = false))
    }

    @Test fun normalChatPreservesUserGreaterThanText() {
        assertEquals("> hello", ChatFormat.format(" > hello", random = false))
    }

    @Test fun customTextAppearsAtStart() {
        assertEquals("my custom text hello", ChatFormat.format("hello", "my custom text", random = false))
    }

    @Test fun randomTailIsAlphanumeric() {
        repeat(100) {
            val result = ChatFormat.format("hello")
            // Note: 'i' is intentionally excluded from the junk character set.
            assertTrue(result.matches(Regex("hello \\| [a-hj-z0-9]{12,22}")))
        }
    }
}
