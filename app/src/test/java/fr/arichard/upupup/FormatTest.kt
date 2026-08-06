package fr.arichard.upupup

import fr.arichard.upupup.core.Format
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun `stopwatch shows hundredths`() {
        assertEquals("00:00.00", Format.stopwatch(0))
        assertEquals("00:00.05", Format.stopwatch(59))
        assertEquals("00:01.23", Format.stopwatch(1_234))
        assertEquals("59:59.99", Format.stopwatch(3_599_999))
    }

    @Test
    fun `stopwatch grows an hours field past an hour`() {
        assertEquals("1:00:00.00", Format.stopwatch(3_600_000))
        assertEquals("2:03:04.56", Format.stopwatch(2 * 3_600_000L + 184_567))
    }
}
