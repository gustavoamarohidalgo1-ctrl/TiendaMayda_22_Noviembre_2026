package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.ProductSalesActivity
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class ScannerBarcodeFamiliesTest {
    private val businessId = BusinessId.from(UUID(0L, 1L))
    private val primary = product(1, "7751234500004")
    private val first = product(2, "751234500004")
    private val second = product(3, "7123450000")

    private fun product(id: Long, code: String) = Product(
        productId = ProductId.from(UUID(0L, id)),
        businessId = businessId,
        unitId = UnitId.from(UUID(0L, 1L)),
        name = "Jabón",
        barcode = code,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    @Test
    fun `only unambiguously attributable positional twins form a family in catalog order`() {
        val catalog = listOf(second, primary, product(4, "51234500004"), first)
        assertEquals(listOf(BarcodeFamily(primary, listOf(second, first))), BarcodeFamilies.find(catalog))
        val otherPrincipal = primary.copy(productId = ProductId.from(UUID(0L, 5L)))
        assertTrue(BarcodeFamilies.find(catalog + otherPrincipal).isEmpty())
        val foreign = first.copy(businessId = BusinessId.from(UUID(0L, 2L)))
        assertTrue(BarcodeFamilies.find(listOf(primary, foreign)).isEmpty())
    }

    @Test
    fun `real sales activity breaks ties and archived products never displace an active twin`() {
        val family = BarcodeFamily(primary, listOf(first, second))
        assertEquals(primary, BarcodeFamilies.preferred(family, emptyMap()))
        val activity = mapOf(
            first.productId to ProductSalesActivity(2, 300),
            second.productId to ProductSalesActivity(3, 100),
        )
        assertEquals(second, BarcodeFamilies.preferred(family, activity))
        assertEquals(first, BarcodeFamilies.preferred(family, activity + (first.productId to ProductSalesActivity(3, 200))))
        assertEquals(first, BarcodeFamilies.preferred(family.copy(twins = listOf(first, second.copy(status = CatalogStatus.ARCHIVED))), activity))
    }

    @Test
    fun `group selection follows its keeper and ungrouped variants use sales activity`() {
        val members = listOf(primary, first, second)
        val group = BarcodeGroup(members, second.productId)
        assertEquals(second, BarcodeFamilies.choose("7123450000", members, group, emptyMap()))
        assertEquals(primary, BarcodeFamilies.choose("7123450000", listOf(primary), null, emptyMap()))
        assertEquals(first, BarcodeFamilies.choose("7751234500004", listOf(first, second), null, emptyMap()))
        assertNull(BarcodeFamilies.choose("7123450000", listOf(first, second), null, emptyMap()))
    }

    @Test
    fun `name comparisons ignore accents packaging quantities numbers and stopwords`() {
        assertTrue(ScannerDuplicateNames.areRelated("JABÓN 3 pack", "Jabón personal"))
        assertFalse(ScannerDuplicateNames.areRelated("Cola 2 litros", "Cola 2 litros"))
        assertFalse(ScannerDuplicateNames.areRelated("B12 chico", "B12 chica"))
        assertFalse(ScannerDuplicateNames.areRelated("Sal", "Salsa"))
    }
}
