package org.example.project.admin.features.product.presentation

import org.example.project.admin.features.product.presentation.components.ProductManagerActions
import org.example.project.core.domain.dto.product.ProductCategoryDto
import org.example.project.core.domain.dto.product.ProductDto

/**
 * What the three list layouts can ask the screen to do. Every layout gets the same set, so a layout
 * only decides where a control sits, never what it does; the screen wraps the opens in the leave guard.
 */
class ProductScreenActions(
    val createProduct: () -> Unit,
    val editProduct: (ProductDto.ProductResponseDto) -> Unit,
    val deleteProduct: (ProductDto.ProductResponseDto) -> Unit,
    val createCategory: () -> Unit,
    val editCategory: (ProductCategoryDto.ProductCategoryResponseDto) -> Unit,
    val deleteCategory: (ProductCategoryDto.ProductCategoryResponseDto) -> Unit,
    val managers: ProductManagerActions,
)
