package org.example.project.features.maintenance

import org.example.project.features.maintenance.data.logs.BoundedLineReader
import org.example.project.features.maintenance.data.logs.ScanBudget
import java.io.Reader
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoundedLineReaderTest {
    private fun budget(maxChars: Long = Long.MAX_VALUE) = ScanBudget(maxChars, Long.MAX_VALUE, System::nanoTime)

    private fun readAll(reader: BoundedLineReader): List<String> = generateSequence { reader.readLine() }.toList()

    /** An endless line: what a 2 GB file without a newline looks like to the reader. */
    private class EndlessReader : Reader() {
        var served = 0L

        override fun read(
            cbuf: CharArray,
            off: Int,
            len: Int,
        ): Int {
            cbuf.fill('a', off, off + len)
            served += len
            return len
        }

        override fun close() = Unit
    }

    @Test
    fun `lines are split on newline, a carriage return is dropped and a last line without newline is kept`() {
        val reader = BoundedLineReader(StringReader("one\r\ntwo\n\nthree"), budget())
        assertEquals(listOf("one", "two", "", "three"), readAll(reader))
    }

    @Test
    fun `a long line is cut, the rest of it is discarded and the next line is intact`() {
        val reader = BoundedLineReader(StringReader("x".repeat(100_000) + "\nnext\n"), budget(), maxLineChars = 1_000)
        assertEquals(listOf("x".repeat(1_000), "next"), readAll(reader))
    }

    @Test
    fun `every character read is charged to the budget, discarded ones included`() {
        val budget = budget(maxChars = 50_000)
        val reader = BoundedLineReader(StringReader("x".repeat(100_000) + "\nnext\n"), budget, maxLineChars = 1_000)
        reader.readLine()
        assertTrue(budget.exhausted(), "100,000 characters were read but the budget of 50,000 is not spent")
    }

    @Test
    fun `an endless line stops once the budget is spent`() {
        val source = EndlessReader()
        val budget = budget(maxChars = 1_000_000)
        val reader = BoundedLineReader(source, budget)
        assertNull(reader.readLine())
        assertTrue(budget.exhausted())
        assertTrue(source.served < 1_100_000, "read ${source.served} characters for a budget of 1,000,000")
        assertFalse(source.served == 0L)
    }
}
