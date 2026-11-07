package org.example.project.testutils

import org.junit.jupiter.api.Test

class TestDataExtensionsTest {
    @Test
    fun `can create product category`() =
        testApp(
            setupData = { TestData.createCategory() },
        ) { _ -> }

    @Test
    fun `can create product`() =
        testApp(
            setupData = {
                val cat = TestData.createCategory()
                TestData.createProduct(categoryId = cat.id.value)
            },
        ) { _ -> }

    @Test
    fun `can create table with session`() =
        testApp(
            setupData = { TestData.createTableWithSession() },
        ) { _ -> }

    @Test
    fun `can create VAT rates`() =
        testApp(
            setupData = { TestData.createDefaultVatRates() },
        ) { _ -> }

    @Test
    fun `can create payment type`() =
        testApp(
            setupData = { TestData.createPaymentType() },
        ) { _ -> }

    @Test
    fun `can create shop info`() =
        testApp(
            setupData = { TestData.createShopInfo() },
        ) { _ -> }
}
