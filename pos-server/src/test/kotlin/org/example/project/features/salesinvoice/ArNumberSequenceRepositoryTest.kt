package org.example.project.features.salesinvoice

import org.example.project.features.salesinvoice.data.repository.ArNumberSequenceRepositoryImpl
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ArNumberSequenceRepositoryTest {
    private val repo = ArNumberSequenceRepositoryImpl()

    @Test
    fun `the first number of a year is one, zero-padded`() {
        testApp {
            transaction {
                assertEquals("RG-2027-00001", repo.nextNumber(prefix = "RG", year = 2027))
            }
        }
    }

    @Test
    fun `numbers increment without gaps`() {
        testApp {
            transaction {
                val issued = (1..5).map { repo.nextNumber(prefix = "RG", year = 2027) }
                assertEquals(
                    listOf("RG-2027-00001", "RG-2027-00002", "RG-2027-00003", "RG-2027-00004", "RG-2027-00005"),
                    issued,
                )
            }
        }
    }

    @Test
    fun `each year has its own counter`() {
        testApp {
            transaction {
                repo.nextNumber(prefix = "RG", year = 2027)
                repo.nextNumber(prefix = "RG", year = 2027)

                assertEquals("RG-2028-00001", repo.nextNumber(prefix = "RG", year = 2028))
                assertEquals("RG-2027-00003", repo.nextNumber(prefix = "RG", year = 2027))
            }
        }
    }

    @Test
    fun `a start value shifts the first number only`() {
        testApp {
            transaction {
                assertEquals("RG-2027-00144", repo.nextNumber(prefix = "RG", year = 2027, startAt = 144L))
                assertEquals(
                    "RG-2027-00145",
                    repo.nextNumber(prefix = "RG", year = 2027, startAt = 999L),
                    "startAt seeds a new counter; it never resets a running one",
                )
            }
        }
    }

    @Test
    fun `padding is a floor, not a truncation`() {
        testApp {
            transaction {
                assertEquals(
                    "RG-2027-100000",
                    repo.nextNumber(prefix = "RG", year = 2027, startAt = 100_000L),
                    "a number wider than the padding is printed in full, never cut",
                )
            }
        }
    }
}
