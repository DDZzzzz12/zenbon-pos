package org.example.project.core.domain.dto.debtor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The server's debtor guard is these `validate()` calls (NEW-20): name required, e-mail shaped if given. */
class DebtorDtoValidationTest {
    @Test
    fun `a blank name and a malformed e-mail are both reported`() {
        assertEquals(
            listOf(DebtorDto.FIELD_NAME, DebtorDto.FIELD_EMAIL),
            DebtorDto.CreateRequestDto(name = " ", email = "kaputt").validate(),
        )
        assertEquals(listOf(DebtorDto.FIELD_EMAIL), DebtorDto.UpdateRequestDto(name = "Acme", email = "a@b").validate())
    }

    @Test
    fun `an absent, blank or plausible e-mail passes`() {
        assertTrue(DebtorDto.CreateRequestDto(name = "Acme").validate().isEmpty())
        assertTrue(DebtorDto.CreateRequestDto(name = "Acme", email = "").validate().isEmpty())
        assertTrue(DebtorDto.UpdateRequestDto(name = "Acme", email = "rechnung@acme.de").validate().isEmpty())
    }
}
