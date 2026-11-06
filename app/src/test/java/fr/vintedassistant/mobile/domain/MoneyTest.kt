package fr.vintedassistant.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {

    @Test
    fun parseAcceptsCommaAndDot() {
        assertEquals(1250L, Money.parseEuros("12,50"))
        assertEquals(1250L, Money.parseEuros("12.5"))
        assertEquals(1200L, Money.parseEuros("12"))
        assertEquals(1200L, Money.parseEuros("12."))
        assertEquals(0L, Money.parseEuros("0"))
    }

    @Test
    fun parseIgnoresSpacesAndEuroSign() {
        assertEquals(700L, Money.parseEuros(" 7 € "))
        assertEquals(123456L, Money.parseEuros("1 234,56"))
        assertEquals(123456L, Money.parseEuros("1 234,56 €"))
    }

    @Test
    fun parseRejectsInvalidInput() {
        assertNull(Money.parseEuros(""))
        assertNull(Money.parseEuros("   "))
        assertNull(Money.parseEuros("-3"))
        assertNull(Money.parseEuros("abc"))
        assertNull(Money.parseEuros("1,234"))
        assertNull(Money.parseEuros("1,2,3"))
        assertNull(Money.parseEuros(",5"))
    }

    @Test
    fun parseRejectsAmountAboveCeiling() {
        assertEquals(Money.MAX_CENTS, Money.parseEuros("1000000"))
        assertNull(Money.parseEuros("1000000,01"))
        assertNull(Money.parseEuros("99999999999999999999999"))
    }

    @Test
    fun formatUsesFrenchTypography() {
        assertEquals("12,50 €", Money.formatEuros(1250))
        assertEquals("0,05 €", Money.formatEuros(5))
        assertEquals("0,00 €", Money.formatEuros(0))
        assertEquals("1 234,56 €", Money.formatEuros(123456))
        assertEquals("1 000 000,00 €", Money.formatEuros(100_000_000))
        assertEquals("-0,50 €", Money.formatEuros(-50))
        assertEquals("-1 234,00 €", Money.formatEuros(-123400))
    }

    @Test
    fun parseThenFormatRoundTrips() {
        for (text in listOf("0,01", "9,99", "19,99", "250,00", "1 234,56")) {
            val cents = Money.parseEuros(text)!!
            assertEquals(text + " €", Money.formatEuros(cents))
        }
    }
}
