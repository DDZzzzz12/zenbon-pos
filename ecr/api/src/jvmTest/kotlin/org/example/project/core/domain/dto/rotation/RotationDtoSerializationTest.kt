package org.example.project.core.domain.dto.rotation

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.order.OrderDto
import org.example.project.core.domain.dto.websocket.NotificationDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class RotationDtoSerializationTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    @Test
    fun updateRoundTripsThroughNotificationMessage() {
        val original: NotificationDto.NotificationMessage =
            NotificationDto.RotationNextUpdateDto(
                timestamp = "2026-10-16T10:00:00Z",
                version = 7,
                next = RotationDto.EmployeeRefDto("id", "12", "Anna"),
            )
        val decoded = json.decodeFromString<NotificationDto.NotificationMessage>(json.encodeToString(original))
        assertEquals(original, decoded)
    }

    @Test
    fun nullNextRoundTrips() {
        val original: NotificationDto.NotificationMessage =
            NotificationDto.RotationNextUpdateDto(
                timestamp = "2026-10-16T10:00:00Z",
                version = 8,
                next = null,
                isSnapshot = true,
            )
        val decoded = json.decodeFromString<NotificationDto.NotificationMessage>(json.encodeToString(original))
        assertEquals(original, decoded)
    }

    @Test
    fun oldOrderRequestJsonWithoutExpectationDecodes() {
        val request =
            json.decodeFromString<OrderDto.CreateOrderRequestDto>("""{"tableInfoId":1,"orderItems":[]}""")
        assertNull(request.expectedNextEmployeeId)
    }

    @Test
    fun oldOrderResponseJsonDecodesWithShiftedFalse() {
        val response =
            json.decodeFromString<OrderDto.OrderResponseDto>(
                """{"orderId":1,"orderNumber":"1","employeeId":"e","tableNumber":"1","orderTime":"t","orderTotal":"1.00"}""",
            )
        assertFalse(response.rotationShifted)
        assertNull(response.rotationOwner)
    }
}
