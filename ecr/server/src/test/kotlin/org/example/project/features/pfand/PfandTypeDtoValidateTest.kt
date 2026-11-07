package org.example.project.features.pfand

import org.example.project.core.domain.dto.pfand.PfandTypeDto
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class PfandTypeDtoValidateTest {
    @Test fun `valid request passes`() {
        assertTrue(PfandTypeDto.CreatePfandTypeRequestDto("Einweg", "0.25", "A").validate().isEmpty())
    }

    @Test fun `blank name, non-positive value, bad vat class are rejected`() {
        assertTrue(PfandTypeDto.CreatePfandTypeRequestDto("", "0.25", "A").validate().any { it.contains("Name") })
        assertTrue(PfandTypeDto.CreatePfandTypeRequestDto("X", "0", "A").validate().any { it.contains("positive") })
        assertTrue(PfandTypeDto.CreatePfandTypeRequestDto("X", "-1", "A").validate().any { it.contains("positive") })
        assertTrue(PfandTypeDto.CreatePfandTypeRequestDto("X", "0.25", "Z").validate().any { it.contains("VAT class") })
    }
}
