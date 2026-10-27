package vbh.ai.whatsapp

import org.junit.Assert.assertEquals
import org.junit.Test

class MentionsTest {
    private val names = mapOf("111@lid" to "Ana", "4477@s.whatsapp.net" to "+4477")

    @Test fun eachMentionReadsAsItsName() =
        assertEquals("@Ana and @+4477, hi", Mentions.shown("@111 and @4477, hi", names.keys.toList(), names::get))

    @Test fun aLongerNumberIsNotAMention() = assertEquals("@1112 @Ana", Mentions.shown("@1112 @111", listOf("111@lid"), names::get))

    @Test fun anUnknownNameKeepsTheTokenAndPlainTextIsUntouched() {
        assertEquals("@999 hi", Mentions.shown("@999 hi", listOf("999@lid"), names::get))
        assertEquals("mail me@111", Mentions.shown("mail me@111", emptyList(), names::get))
    }

    @Test fun aNameWithDollarSignsIsInsertedLiterally() =
        assertEquals("@\$1 Club", Mentions.shown("@111", listOf("111@lid"), mapOf("111@lid" to "\$1 Club")::get))

    @Test fun pickedNamesAreSentAsTheirUserAndListed() {
        val picked = listOf("Ana" to "111@lid", "Ana Maria" to "222@lid", "Bo" to "333@lid")
        assertEquals("@111 and @222, hi" to listOf("222@lid", "111@lid"), Mentions.toWire("@Ana and @Ana Maria, hi", picked))
    }

    @Test fun aMentionWhoseNameWasDeletedIsNotSent() =
        assertEquals(
            "hi Bo" to emptyList<String>(),
            Mentions.toWire(
                "hi Bo",
                listOf("Bo" to "333@lid"),
            ),
        )

    @Test fun onlyAnAtTypedAtAWordStartOpensThePicker() {
        assertEquals(true, typedMentionStart("", "@", 1))
        assertEquals(true, typedMentionStart("hi ", "hi @", 4))
        assertEquals(false, typedMentionStart("me", "me@", 3))
        assertEquals(false, typedMentionStart("hi", "hi @", 4))
    }
}
