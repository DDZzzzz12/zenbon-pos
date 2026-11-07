package org.example.project.core.domain.enums.product

enum class VisibilityScope {
    EVERYWHERE,
    POS_ONLY,
    WEBSITE_ONLY,
    HIDDEN,
    ;

    fun isVisibleOnWebsite(): Boolean = this == EVERYWHERE || this == WEBSITE_ONLY

    fun isVisibleInPos(): Boolean = this == EVERYWHERE || this == POS_ONLY
}
