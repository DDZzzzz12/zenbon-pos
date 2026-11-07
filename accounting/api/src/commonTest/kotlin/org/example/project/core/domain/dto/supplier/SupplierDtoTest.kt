package org.example.project.core.domain.dto.supplier

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SupplierDtoTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    @Test
    fun `a response round-trips`() {
        val dto =
            SupplierDto.ResponseDto(
                id = 7,
                name = "Metro AG",
                vatId = "DE123456789",
                creditorNo = "70001",
                knownIbans = listOf("DE89370400440532013000"),
            )
        val decoded = json.decodeFromString<SupplierDto.ResponseDto>(json.encodeToString(dto))
        assertEquals(dto, decoded)
    }

    @Test
    fun `a create request rejects a blank name`() {
        assertTrue(SupplierDto.CreateRequestDto(name = "  ").validate().isNotEmpty())
        assertTrue(SupplierDto.CreateRequestDto(name = "Metro AG").validate().isEmpty())
    }

    @Test
    fun `a request reports a malformed e-mail, never a blank one`() {
        assertEquals(listOf("email"), SupplierDto.CreateRequestDto(name = "Metro AG", email = "kaputt").validate())
        assertEquals(listOf("email"), SupplierDto.UpdateRequestDto(name = "Metro AG", email = "info@metro").validate())
        assertTrue(SupplierDto.CreateRequestDto(name = "Metro AG", email = " ").validate().isEmpty())
        assertTrue(SupplierDto.UpdateRequestDto(name = "Metro AG", email = "info@metro.de").validate().isEmpty())
    }
}
