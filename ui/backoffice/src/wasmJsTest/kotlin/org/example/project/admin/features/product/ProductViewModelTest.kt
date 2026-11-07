package org.example.project.admin.features.product

import kotlinx.coroutines.test.runTest
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.choicegroup.FakeChoiceGroupApiClient
import org.example.project.admin.features.product.presentation.CategoryEditor
import org.example.project.admin.features.product.presentation.ProductEditor
import org.example.project.admin.features.product.presentation.ProductSort
import org.example.project.admin.features.product.presentation.ProductState
import org.example.project.admin.features.product.presentation.ProductViewModel
import org.example.project.admin.features.product.presentation.withEditedErrorsCleared
import org.example.project.core.domain.dto.pfand.PfandTypeDto
import org.example.project.core.domain.dto.product.ChoiceGroupDto
import org.example.project.core.domain.dto.product.ProductCategoryDto
import org.example.project.core.domain.dto.product.ProductChoiceGroupDto
import org.example.project.core.domain.dto.product.ProductDto
import org.example.project.core.network.error.NetworkApiException
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.keys.BackOfficeErrorKeys
import org.example.project.core.utils.file.PickedFile
import org.example.project.ui.components.inventory.CategoryFormField
import org.example.project.ui.components.inventory.ProductFormField
import org.example.project.ui.components.inventory.isPartiallyAssigned
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun cat(
    id: Int,
    name: String,
) = ProductCategoryDto.ProductCategoryResponseDto(
    id = id,
    name = name,
    description = null,
    isActive = true,
    sortPosition = 0.0,
    updatedAt = "2026-07-11",
)

private fun prod(
    id: Long,
    name: String,
    catId: Int,
    price: String,
) = ProductDto.ProductResponseDto(
    id = id,
    productNumber = "P$id",
    name = name,
    description = null,
    price = price,
    categoryId = catId,
    categoryName = "cat",
    productType = "FOOD",
    isActive = true,
)

private fun group(
    id: Int,
    name: String,
) = ChoiceGroupDto.ChoiceGroupResponseDto(
    id = id,
    name = name,
    minSelections = 0,
    maxSelections = 1,
    isActive = true,
    updatedAt = "2026-07-11",
)

private fun vm(
    products: List<ProductDto.ProductResponseDto> = emptyList(),
    categories: List<ProductCategoryDto.ProductCategoryResponseDto> = emptyList(),
    fakeProducts: FakeProductApiClient = FakeProductApiClient(products),
    fakeCategories: FakeProductCategoryApiClient = FakeProductCategoryApiClient(categories),
    fakeProductChoiceGroups: FakeProductChoiceGroupApiClient = FakeProductChoiceGroupApiClient(),
    fakeChoiceGroups: FakeChoiceGroupApiClient = FakeChoiceGroupApiClient(),
    fakePfandTypes: FakePfandTypeApiClient = FakePfandTypeApiClient(),
) = ProductViewModel(fakeProducts, fakeCategories, fakeProductChoiceGroups, fakeChoiceGroups, fakePfandTypes)

private val einweg = PfandTypeDto.PfandTypeResponseDto(id = 4, name = "Einweg 0,25", depositValue = "0.25", vatClass = "A", isActive = true)

class ProductViewModelTest {
    @Test
    fun load_populatesDataWithCategoriesAndProducts() =
        runTest {
            val model =
                vm(
                    products = listOf(prod(1, "Cola", 10, "2.50"), prod(2, "Fries", 20, "3.00")),
                    categories = listOf(cat(10, "Drinks"), cat(20, "Sides")),
                )
            model.refresh()
            val data = assertIs<ProductState.Data>(model.state)
            assertEquals(2, data.categories.size)
            assertEquals(2, data.visibleProducts.size)
        }

    @Test
    fun refresh_keepsAppliedSearchCategoryAndSort() =
        runTest {
            val model =
                vm(
                    products = listOf(prod(1, "Cola", 10, "2.50"), prod(2, "Fries", 20, "3.00")),
                    categories = listOf(cat(10, "Drinks"), cat(20, "Sides")),
                )
            model.refresh()
            model.selectCategory(20)
            model.onSearch("fr")
            model.onSort(ProductSort.PRICE_DESC)

            model.refresh()

            val data = assertIs<ProductState.Data>(model.state)
            assertEquals(20, data.selectedCategoryId)
            assertEquals("fr", data.search)
            assertEquals(ProductSort.PRICE_DESC, data.sort)
            assertEquals(listOf("Fries"), data.visibleProducts.map { it.name })
        }

    @Test
    fun refresh_dropsACategoryFilterWhoseCategoryNoLongerExists() =
        runTest {
            val fakeCategories = FakeProductCategoryApiClient(listOf(cat(10, "Drinks"), cat(20, "Sides")))
            val model = vm(products = listOf(prod(1, "Cola", 10, "2.50")), fakeCategories = fakeCategories)
            model.refresh()
            model.selectCategory(20)
            fakeCategories.categories = listOf(cat(10, "Drinks"))

            model.refresh()

            assertEquals(null, assertIs<ProductState.Data>(model.state).selectedCategoryId)
        }

    @Test
    fun selectCategory_filtersProducts() =
        runTest {
            val model =
                vm(
                    products = listOf(prod(1, "Cola", 10, "2.50"), prod(2, "Fries", 20, "3.00")),
                    categories = listOf(cat(10, "Drinks"), cat(20, "Sides")),
                )
            model.refresh()
            model.selectCategory(10)
            val data = assertIs<ProductState.Data>(model.state)
            assertEquals(listOf("Cola"), data.visibleProducts.map { it.name })
        }

    @Test
    fun search_filtersByName() =
        runTest {
            val model = vm(products = listOf(prod(1, "Cola", 10, "2.50"), prod(2, "Fries", 20, "3.00")))
            model.refresh()
            model.onSearch("fri")
            val data = assertIs<ProductState.Data>(model.state)
            assertEquals(listOf("Fries"), data.visibleProducts.map { it.name })
        }

    @Test
    fun sortByPriceDesc_ordersProducts() =
        runTest {
            val model = vm(products = listOf(prod(1, "Cola", 10, "2.50"), prod(2, "Fries", 20, "3.00")))
            model.refresh()
            model.onSort(ProductSort.PRICE_DESC)
            val data = assertIs<ProductState.Data>(model.state)
            assertEquals(listOf("Fries", "Cola"), data.visibleProducts.map { it.name })
        }

    @Test
    fun loadFailure_yieldsError() =
        runTest {
            val failing =
                FakeProductApiClient(onError = {
                    throw org.example.project.core.network.error
                        .NetworkApiException(RuntimeException("down"))
                })
            val model = vm(fakeProducts = failing)
            model.refresh()
            assertTrue(model.state is ProductState.Error)
            assertIs<UiError.Network>((model.state as ProductState.Error).error)
        }

    @Test
    fun saveNewProduct_withImage_uploadsThenCreates() =
        runTest {
            val fp = FakeProductApiClient()
            val model = vm(fakeProducts = fp, categories = listOf(cat(10, "Drinks")))
            model.refresh()
            model.openCreateProduct()
            model.onEditorField {
                it.copy(
                    productNumber = "P100",
                    name = "Water",
                    price = "1.50",
                    categoryId = 10,
                    productType = "FOOD",
                    imagePicked = PickedFile("w.jpg", "image/jpeg", byteArrayOf(1, 2, 3)),
                )
            }
            model.saveEditor()
            assertEquals("w.jpg", fp.uploadedFileName)
            val created = kotlin.test.assertNotNull(fp.created)
            assertEquals("Water", created.name)
            assertEquals("api/products/images/w.jpg", created.imageUrl)
        }

    @Test
    fun deleteProduct_callsApi_andReloads() =
        runTest {
            val fp = FakeProductApiClient(listOf(prod(1, "Cola", 10, "2.50")))
            val model = vm(fakeProducts = fp)
            model.refresh()
            model.deleteProduct(1)
            assertEquals(1L, fp.deletedId)
        }

    @Test
    fun saveNewCategory_callsCreate() =
        runTest {
            val fc = FakeProductCategoryApiClient()
            val model = vm(fakeCategories = fc)
            model.refresh()
            model.openCreateCategory()
            model.onCategoryEditorField { it.copy(name = "Desserts") }
            model.saveCategoryEditor()
            assertEquals("Desserts", kotlin.test.assertNotNull(fc.created).name)
        }

    @Test
    fun saveCategory_blankName_setsNameError_doesNotCallApi() =
        runTest {
            val fc = FakeProductCategoryApiClient()
            val model = vm(fakeCategories = fc)
            model.refresh()
            model.openCreateCategory()
            model.saveCategoryEditor()
            assertEquals(null, fc.created, "an empty name may not reach the server")
            val refused = assertNotNull(model.categoryEditor)
            assertEquals(mapOf(CategoryFormField.NAME to AdminStringKeys.ProductFieldRequired), refused.fieldErrors)
            assertEquals(1, refused.validationAttempt)
            assertEquals(false, refused.saving)

            model.saveCategoryEditor()
            assertEquals(2, assertNotNull(model.categoryEditor).validationAttempt, "every refused Save scrolls again")

            model.onCategoryEditorField { it.copy(name = "Desserts") }
            assertEquals(emptyMap(), assertNotNull(model.categoryEditor).fieldErrors, "typing a name clears its error")
            model.saveCategoryEditor()
            assertEquals("Desserts", assertNotNull(fc.created).name)
        }

    @Test
    fun categoryEditor_validationStateIsNotAnEdit() {
        val opened = CategoryEditor(id = 2, name = "Drinks")
        val refused = opened.copy(fieldErrors = mapOf(CategoryFormField.NAME to AdminStringKeys.ProductFieldRequired), validationAttempt = 3)
        assertEquals(false, refused.isDirtyComparedTo(opened))
    }

    @Test
    fun openEditProduct_loadsAttachedGroupIds() =
        runTest {
            val fpg =
                FakeProductChoiceGroupApiClient(
                    groupsForProduct =
                        ProductChoiceGroupDto.ProductGroupsResponseDto(
                            productId = 1,
                            groups = listOf(group(3, "Size"), group(1, "Toppings")),
                        ),
                )
            val model = vm(products = listOf(prod(1, "Cola", 10, "2.50")), fakeProductChoiceGroups = fpg)
            model.refresh()
            model.openEditProduct(prod(1, "Cola", 10, "2.50"))
            assertEquals(listOf(3, 1), assertNotNull(model.editor).attachedGroupIds)
        }

    @Test
    fun saveEditor_existingProduct_attachesOrderedGroupIds() =
        runTest {
            val fp = FakeProductApiClient(listOf(prod(1, "Cola", 10, "2.50")))
            val fpg = FakeProductChoiceGroupApiClient()
            val model = vm(fakeProducts = fp, categories = listOf(cat(10, "Drinks")), fakeProductChoiceGroups = fpg)
            model.refresh()
            model.openEditProduct(prod(1, "Cola", 10, "2.50"))
            model.attachGroup(5)
            model.attachGroup(2)
            model.saveEditor()
            val req = assertNotNull(fpg.lastAttach)
            assertEquals(1L, req.productId)
            assertEquals(listOf(5, 2), req.groupIds)
        }

    @Test
    fun refresh_loadsActivePfandTypes_andToleratesTheirFailure() =
        runTest {
            val loaded = vm(fakePfandTypes = FakePfandTypeApiClient(listOf(einweg)))
            loaded.refresh()
            assertEquals(listOf(4L), assertIs<ProductState.Data>(loaded.state).pfandTypes.map { it.id })

            val failing = FakePfandTypeApiClient().apply { failWith = IllegalStateException("boom") }
            val tolerant = vm(fakePfandTypes = failing)
            tolerant.refresh()
            assertTrue(assertIs<ProductState.Data>(tolerant.state).pfandTypes.isEmpty(), "a Pfand-type failure must not fail the catalogue")
        }

    /**
     * The server reads an absent `pfandTypeId` as "remove the link", so an edit that does not touch
     * the selector must still send the current link back — otherwise every save silently unlinked
     * the product (and orphaned a Pfand type's own deposit article from its type).
     */
    @Test
    fun saveEditor_existingProduct_sendsCurrentPfandTypeBack() =
        runTest {
            val linked = prod(1, "Cola", 10, "2.50").copy(pfandTypeId = 4, pfandTypeName = "Einweg 0,25")
            val fp = FakeProductApiClient(listOf(linked))
            val model = vm(fakeProducts = fp, categories = listOf(cat(10, "Drinks")))
            model.refresh()
            model.openEditProduct(linked)
            model.onEditorField { it.copy(name = "Cola Zero") }
            model.saveEditor()
            assertEquals(4L, assertNotNull(fp.updated).pfandTypeId)
        }

    @Test
    fun saveEditor_newProduct_sendsSelectedPfandType() =
        runTest {
            val fp = FakeProductApiClient()
            val model = vm(fakeProducts = fp, categories = listOf(cat(10, "Drinks")), fakePfandTypes = FakePfandTypeApiClient(listOf(einweg)))
            model.refresh()
            model.openCreateProduct()
            model.onEditorField {
                it.copy(
                    name = "Cola",
                    productNumber = "COLA",
                    categoryId = 10,
                    productType = "DRINKS",
                    pfandTypeId = 4,
                    pfandTypeName = "Einweg 0,25",
                )
            }
            model.saveEditor()
            assertEquals(4L, assertNotNull(fp.created).pfandTypeId)
        }

    @Test
    fun openEditProduct_marksPfandArticleReadOnly() =
        runTest {
            val article = prod(9, "Einweg 0,25 Pfand", 10, "0.25").copy(isDeposit = true, pfandTypeId = 4, pfandTypeName = "Einweg 0,25")
            val model = vm(products = listOf(article))
            model.refresh()
            model.openEditProduct(article)
            val editor = assertNotNull(model.editor)
            assertTrue(editor.isPfandArticle)
            assertEquals(4L, editor.pfandTypeId)
        }

    @Test
    fun bulkAttachGroupToCategory_callsApi() =
        runTest {
            val fpg = FakeProductChoiceGroupApiClient()
            val model = vm(fakeProductChoiceGroups = fpg)
            model.refresh()
            model.bulkAttachGroupToCategory(categoryId = 3, groupId = 7)
            assertEquals(
                ProductChoiceGroupDto.BulkCategoryAttachRequestDto(categoryId = 3, groupIds = listOf(7)),
                fpg.lastBulkAttach,
            )
        }

    @Test
    fun bulkRemoveGroupFromCategory_callsApi() =
        runTest {
            val fpg = FakeProductChoiceGroupApiClient()
            val model = vm(fakeProductChoiceGroups = fpg)
            model.refresh()
            model.bulkRemoveGroupFromCategory(categoryId = 3, groupId = 7)
            assertEquals(3 to 7, fpg.lastBulkRemove)
        }

    // FO-54: a new product has no silent category/type default (the type drives the VAT mapping).
    @Test
    fun openCreateProduct_hasNoTypeAndNoCategoryWithoutAFilter() =
        runTest {
            val model = vm(categories = listOf(cat(10, "Drinks"), cat(20, "Sides")))
            model.refresh()
            model.openCreateProduct()
            val editor = assertNotNull(model.editor)
            assertEquals(null, editor.productType)
            assertEquals(null, editor.categoryId)
        }

    @Test
    fun openCreateProduct_preselectsOnlyTheFilteredCategory() =
        runTest {
            val model = vm(categories = listOf(cat(10, "Drinks"), cat(20, "Sides")))
            model.refresh()
            model.selectCategory(20)
            model.openCreateProduct()
            val editor = assertNotNull(model.editor)
            assertEquals(20, editor.categoryId)
            assertEquals(null, editor.productType, "the type is never preselected")
        }

    @Test
    fun saveEditor_newProduct_isNotSentUntilCategoryAndTypeAreChosen() =
        runTest {
            val fp = FakeProductApiClient()
            val model = vm(fakeProducts = fp, categories = listOf(cat(10, "Drinks")))
            model.refresh()
            model.openCreateProduct()
            model.onEditorField { it.copy(productNumber = "P1", name = "Water", categoryId = 10) }
            model.saveEditor()
            assertEquals(null, fp.created, "no type: nothing may reach the server")
            val refused = assertNotNull(model.editor)
            assertEquals(
                mapOf(ProductFormField.TYPE to AdminStringKeys.ProductFieldRequired),
                refused.fieldErrors,
                "the missing type is reported at the type field",
            )
            assertEquals(1, refused.validationAttempt)

            model.onEditorField { it.copy(productType = "DRINKS") }
            assertEquals(emptyMap(), assertNotNull(model.editor).fieldErrors, "choosing a type clears its error")
            model.saveEditor()
            assertEquals("DRINKS", assertNotNull(fp.created).productType)
        }

    @Test
    fun openEditProduct_keepsTheStoredTypeAndCategory() =
        runTest {
            val model = vm(products = listOf(prod(1, "Cola", 10, "2.50")), categories = listOf(cat(10, "Drinks")))
            model.refresh()
            model.openEditProduct(prod(1, "Cola", 10, "2.50"))
            val editor = assertNotNull(model.editor)
            assertEquals("FOOD", editor.productType)
            assertEquals(10, editor.categoryId)
        }

    // FO-75: a failed bulk operation is surfaced, not swallowed as "best-effort".
    @Test
    fun bulkAttach_failure_landsInTheTypedErrorState() =
        runTest {
            val failing =
                object : FakeProductChoiceGroupApiClient() {
                    override suspend fun bulkAttachToCategory(
                        request: ProductChoiceGroupDto.BulkCategoryAttachRequestDto,
                    ): ProductChoiceGroupDto.BulkAttachResultDto = throw NetworkApiException(RuntimeException("down"))
                }
            val model = vm(fakeProductChoiceGroups = failing)
            model.refresh()
            model.bulkAttachGroupToCategory(categoryId = 3, groupId = 7)
            assertIs<UiError.Network>(model.bulkError)
            model.clearBulkError()
            assertEquals(null, model.bulkError)
        }

    @Test
    fun partialCoverage_isNeitherOnNorOff() {
        assertEquals(true, isPartiallyAssigned(2, 7))
        assertEquals(false, isPartiallyAssigned(0, 7))
        assertEquals(false, isPartiallyAssigned(7, 7))
        assertEquals(false, isPartiallyAssigned(0, 0))
    }

    // QA MD-02: a category holding active products must not be deleted (server answers 409).
    @Test
    fun deleteCategory_withActiveProducts_isBlockedAndNeverSent() =
        runTest {
            val fc = FakeProductCategoryApiClient(listOf(cat(10, "Drinks")))
            val model = vm(products = listOf(prod(1, "Cola", 10, "2.50")), fakeCategories = fc)
            model.refresh()
            val data = assertIs<ProductState.Data>(model.state)
            assertEquals(1, data.activeProductCountFor(10))
            model.deleteCategory(10)
            assertEquals(null, fc.deletedId, "a blocked delete must not reach the server")
        }

    @Test
    fun deleteCategory_withOnlyInactiveProducts_isSent() =
        runTest {
            val fc = FakeProductCategoryApiClient(listOf(cat(10, "Drinks"), cat(20, "Empty")))
            val retired = prod(1, "Old Cola", 10, "2.50").copy(isActive = false)
            val model = vm(products = listOf(retired), fakeCategories = fc)
            model.refresh()
            val data = assertIs<ProductState.Data>(model.state)
            assertEquals(0, data.activeProductCountFor(10))
            model.deleteCategory(10)
            assertEquals(10L, fc.deletedId)
            model.deleteCategory(20)
            assertEquals(20L, fc.deletedId)
        }

    // ----- admin21 NOTE-e21 / NOTE-d21 -----

    @Test
    fun editProduct_seedsPriceWithoutDbScalePadding() =
        runTest {
            val model = vm(fakeProducts = FakeProductApiClient(listOf(prod(1, "Cola", 10, "12.9000"))))
            model.refresh()
            model.openEditProduct(prod(1, "Cola", 10, "12.9000"))
            assertEquals("12.90", model.editor?.price)
        }

    @Test
    fun saveProduct_sendsACommaPriceCanonical() =
        runTest {
            val fp = FakeProductApiClient()
            val model = vm(fakeProducts = fp, categories = listOf(cat(10, "Drinks")))
            model.refresh()
            model.openCreateProduct()
            model.onEditorField { it.copy(productNumber = "P1", name = "Water", price = "12,90", categoryId = 10, productType = "FOOD") }
            model.saveEditor()
            assertEquals("12.90", assertNotNull(fp.created).price)
        }

    @Test
    fun saveProduct_unreadablePrice_setsPriceError_doesNotCallApi() =
        runTest {
            val fp = FakeProductApiClient()
            val model = vm(fakeProducts = fp, categories = listOf(cat(10, "Drinks")))
            model.refresh()
            model.openCreateProduct()
            model.onEditorField { it.copy(productNumber = "P1", name = "Water", price = "12,9x", categoryId = 10, productType = "FOOD") }
            model.saveEditor()
            assertEquals(null, fp.created)
            assertEquals(
                BackOfficeErrorKeys.priceInvalid,
                model.editor
                    ?.fieldErrors
                    ?.get(ProductFormField.PRICE),
            )
        }

    @Test
    fun saveProduct_blankRequiredFields_fillsFieldErrors_doesNotCallApi() =
        runTest {
            val fp = FakeProductApiClient()
            val model = vm(fakeProducts = fp, categories = listOf(cat(10, "Drinks")))
            model.refresh()
            model.openCreateProduct()
            model.saveEditor()
            assertEquals(null, fp.created)
            val errors = assertNotNull(model.editor).fieldErrors
            // FO-54: no category filter is active, so no category is preselected either.
            assertEquals(
                setOf(ProductFormField.NUMBER, ProductFormField.NAME, ProductFormField.CATEGORY, ProductFormField.TYPE),
                errors.keys,
            )
            assertEquals(AdminStringKeys.ProductFieldRequired, errors[ProductFormField.NAME])
            // Editing a field clears only that field's error.
            model.onEditorField { it.copy(name = "Water") }
            assertEquals(
                setOf(ProductFormField.NUMBER, ProductFormField.CATEGORY, ProductFormField.TYPE),
                assertNotNull(model.editor).fieldErrors.keys,
            )
        }

    @Test
    fun withEditedErrorsCleared_clearsOnlyTheChangedFields() {
        val before =
            ProductEditor(
                fieldErrors =
                    mapOf(
                        ProductFormField.NUMBER to AdminStringKeys.ProductFieldRequired,
                        ProductFormField.PRICE to BackOfficeErrorKeys.priceInvalid,
                    ),
            )
        val after = before.copy(price = "3.00").withEditedErrorsCleared(before)
        assertEquals(setOf(ProductFormField.NUMBER), after.fieldErrors.keys)
    }

    @Test
    fun saveProduct_validationAttempt_incrementsOnEachRefusedSave() =
        runTest {
            val model = vm(fakeProducts = FakeProductApiClient(), categories = listOf(cat(10, "Drinks")))
            model.refresh()
            model.openCreateProduct()
            model.saveEditor()
            assertEquals(1, assertNotNull(model.editor).validationAttempt)
            model.saveEditor()
            assertEquals(2, assertNotNull(model.editor).validationAttempt)
        }

    // ----- system records (SYSTEM_RECORD_PROTECTED): the client half of the server refusal -----

    @Test
    fun systemCategories_cannotBeEditedOrDeleted() =
        runTest {
            val pfand = cat(30, "PFAND")
            val voucher = cat(31, "VOUCHER")
            val fc = FakeProductCategoryApiClient(listOf(pfand, voucher, cat(10, "Drinks")))
            val model = vm(categories = fc.categories, fakeCategories = fc)
            model.refresh()

            model.openEditCategory(pfand)
            assertEquals(null, model.categoryEditor, "a system category opens no editor")
            model.openEditCategory(voucher)
            assertEquals(null, model.categoryEditor)
            model.deleteCategory(30)
            model.deleteCategory(31)
            assertEquals(null, fc.deletedId, "a system category delete must not reach the server")

            model.openEditCategory(cat(10, "Drinks"))
            assertNotNull(model.categoryEditor, "an ordinary category stays editable")
            model.deleteCategory(10)
            assertEquals(10L, fc.deletedId, "an ordinary category stays deletable")
        }

    @Test
    fun voucherProduct_cannotBeEditedOrDeleted() =
        runTest {
            val voucher = prod(7, "Voucher", 31, "0.00").copy(productType = "VOUCHER")
            val cola = prod(1, "Cola", 10, "2.50")
            val fp = FakeProductApiClient(listOf(voucher, cola))
            val model = vm(fakeProducts = fp)
            model.refresh()

            model.openEditProduct(voucher)
            assertEquals(null, model.editor, "the voucher article opens no editor")
            model.deleteProduct(7)
            assertEquals(null, fp.deletedId, "the voucher article delete must not reach the server")

            model.openEditProduct(cola)
            assertNotNull(model.editor, "an ordinary product stays editable")
            model.deleteProduct(1)
            assertEquals(1L, fp.deletedId, "an ordinary product stays deletable")
        }
}
