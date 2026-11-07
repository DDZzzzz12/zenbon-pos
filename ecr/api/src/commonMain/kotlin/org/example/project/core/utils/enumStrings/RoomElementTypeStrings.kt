package org.example.project.core.utils.enumStrings

import org.example.project.core.domain.enums.room.RoomElementType
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.getString
import org.example.project.core.presentation.strings.keys.EnumKeys

/**
 * Returns the localized name for this RoomElementType.
 *
 * @param language The target language for localization
 * @return Localized display name for the room element type
 */
fun RoomElementType.getName(language: Language): String =
    when (this) {
        RoomElementType.WALL -> getString(EnumKeys.roomElementTypeWall, language)
        RoomElementType.DOOR -> getString(EnumKeys.roomElementTypeDoor, language)
        RoomElementType.WINDOW -> getString(EnumKeys.roomElementTypeWindow, language)
        RoomElementType.COUNTER -> getString(EnumKeys.roomElementTypeCounter, language)
        RoomElementType.DECORATION -> getString(EnumKeys.roomElementTypeDecoration, language)
    }
