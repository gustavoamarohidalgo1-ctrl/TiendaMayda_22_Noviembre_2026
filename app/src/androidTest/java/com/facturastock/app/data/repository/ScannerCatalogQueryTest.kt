package com.facturastock.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.FailClosedSQLiteOpenHelperFactory
import com.facturastock.app.data.local.entity.InventoryBalanceEntity
import com.facturastock.app.data.local.entity.InventoryLocationEntity
import com.facturastock.app.data.local.entity.SupplierEntity
import com.facturastock.app.data.local.entity.SupplierProductAliasEntity
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.usecase.BarcodeGroup
import com.facturastock.app.domain.repository.SaveSaleCartLineCommand
import com.facturastock.app.domain.repository.SaleCartMutationResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.math.BigDecimal
import java.util.UUID

/**
 * Las lecturas acotadas del escáner deben equivaler exactamente a filtrar la instantánea completa
 * sobre SQLite real: incluyen archivados, excluyen otros negocios y no dependen del orden.
 */
@RunWith(AndroidJUnit4::class)
class ScannerCatalogQueryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = TestClock(Instant.parse("2026-09-23T12:00:00Z"))
    private lateinit var databaseName: String
    private lateinit var database: FacturaStockDatabase
    private lateinit var products: RoomProductRepository

    @Before
    fun setUp() =
        runBlocking {
            databaseName = "scanner-catalog-" + UUID.randomUUID() + ".db"
            database = FacturaStockDatabase.buildNamed(context, FailClosedSQLiteOpenHelperFactory(), databaseName)
            val businesses = RoomBusinessRepository(database.businessDao(), testDispatchers, clock)
            val units = RoomUnitRepository(database.unitDao(), testDispatchers, clock)
            products = RoomProductRepository(database, database.productDao(), testDispatchers, clock)
            listOf(BUSINESS_ID to UNIT_ID, OTHER_BUSINESS_ID to OTHER_UNIT_ID).forEach { (business, unit) ->
                businesses.create(
                    Business(businessId = business, legalName = "Mayda", ruc = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH),
                )
                units.create(
                    UnitOfMeasure(
                        unitId = unit,
                        businessId = business,
                        code = "NIU",
                        name = "Unidad",
                        createdAt = Instant.EPOCH,
                        updatedAt = Instant.EPOCH,
                    ),
                )
            }
        }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun boundedScannerReadsMatchFilteringTheFullCatalog() =
        runBlocking {
            val seeded =
                listOf(
                    product(1, sku = null, barcode = null),
                    product(2, sku = "ARROZ-001", barcode = null),
                    product(3, sku = null, barcode = "753176004930"),
                    product(4, sku = null, barcode = "7753176004930"),
                    product(5, sku = "S-5", barcode = "77531760049301"),
                    product(6, sku = null, barcode = "12345"),
                    product(7, sku = null, barcode = "1234567"),
                ).map { products.create(it) }
            products.archive(seeded[3].productId)
            products.create(product(8, sku = null, barcode = "7753176004931", business = OTHER_BUSINESS_ID))

            val full = products.listForBusiness(BUSINESS_ID)
            assertEquals(7, full.size)
            assertTrue(full.any { it.productId == seeded[3].productId && it.status.name == "ARCHIVED" })

            assertEquals(
                full.filter { it.barcode != null || it.sku != null }.ids(),
                products.listScannerIdentityCandidates(BUSINESS_ID).ids(),
            )
            for (scannedLength in 5..13) {
                val min = scannedLength + 1
                val max = scannedLength + 2
                assertEquals(
                    "longitud $scannedLength",
                    full.filter { product -> product.barcode?.length?.let { it in min..max } == true }.ids(),
                    products.listByBarcodeLength(BUSINESS_ID, min, max).ids(),
                )
            }
        }

    @Test
    fun scannerFamilyMergeConservesStockCostLedgerAndAliasesAndCannotRunTwice() = runBlocking {
        val (main, twin, group) = seedMergeFamily()
        val business = BUSINESS_ID.value
        val stock = database.inventoryDao()
        val aliases = database.supplierProductAliasDao()
        val supplier = UUID.randomUUID().toString()
        database.supplierDao().insert(SupplierEntity(supplier, business, "Proveedor", 1, 1))
        val alias = SupplierProductAliasEntity(UUID.randomUUID().toString(), business, supplier, twin.productId.value, "Arroz especial", 1, 1)
        aliases.insert(alias)
        val beforeOutbox = database.outboxOperationDao().observeForBusiness(business).first().size

        assertTrue(products.mergeScannerGroupIfSafe(BUSINESS_ID, group, main.productId))

        val target = stock.listBalancesForProduct(business, main.productId.value).single()
        assertEquals(0, target.quantityOnHand.toBigDecimal().compareTo(BigDecimal("5")))
        assertEquals(0, target.averageUnitCost.toBigDecimal().compareTo(BigDecimal("3.2")))
        assertEquals("0", stock.listBalancesForProduct(business, twin.productId.value).single().quantityOnHand)
        val adjustments = stock.listMovementsByIdempotencyPattern("product-merge:v1:%")
        assertEquals(2, adjustments.size)
        assertEquals("-3", adjustments.single { it.productId == twin.productId.value }.quantityDelta)
        assertEquals("3", adjustments.single { it.productId == main.productId.value }.quantityDelta)
        assertEquals("ARCHIVED", products.findById(twin.productId)?.status?.name)
        assertNull(products.findById(twin.productId)?.barcode)
        assertEquals(main.productId.value, aliases.findById(alias.aliasId)?.productId)
        assertEquals(beforeOutbox + 1, database.outboxOperationDao().observeForBusiness(business).first().size)
        assertFalse(products.mergeScannerGroupIfSafe(BUSINESS_ID, group, main.productId))
        assertEquals(adjustments, stock.listMovementsByIdempotencyPattern("product-merge:v1:%"))
    }

    @Test
    fun staleStockOrAnOpenCartReferencingTheDiscardedProductPreventsAnyMerge() = runBlocking {
        val (main, twin, stale) = seedMergeFamily()
        val business = BUSINESS_ID.value
        val stock = database.inventoryDao()
        val source = stock.listBalancesForProduct(business, twin.productId.value).single()
        assertEquals(1, stock.updateBalanceIfVersion(
            business, source.productId, source.locationId, source.version, "4", "4", "PEN", source.updatedAt + 1,
        ))
        val before = stock.listBalancesForBusiness(business)
        assertFalse(products.mergeScannerGroupByChoice(BUSINESS_ID, stale, main.productId))
        assertEquals(before, stock.listBalancesForBusiness(business))
        assertTrue(stock.listMovementsByIdempotencyPattern("product-merge:v1:%").isEmpty())

        val current = readMergeGroup(main, twin)
        val sales = RoomSaleRepository(database, clock, UuidGenerator { UUID.randomUUID() }, testDispatchers)
        val cart = sales.createOrResume(BUSINESS_ID, CurrencyCode.of("PEN")).cart
        val added = sales.saveLine(BUSINESS_ID, SaveSaleCartLineCommand(
            saleId = cart.saleId, expectedVersion = cart.version, productId = twin.productId,
            locationId = LocationId.from(UUID.fromString(source.locationId)), quantity = Quantity.of(BigDecimal.ONE),
        ))
        assertTrue(added is SaleCartMutationResult.Saved)
        assertFalse(products.mergeScannerGroupIfSafe(BUSINESS_ID, current, main.productId))
        assertEquals(before, stock.listBalancesForBusiness(business))
        assertTrue(stock.listMovementsByIdempotencyPattern("product-merge:v1:%").isEmpty())
        assertEquals(twin, products.findById(twin.productId))
        assertEquals(main, products.findById(main.productId))
    }

    @Test
    fun zeroStockElsewhereUsesOnlyOneNeutralLinkAndKeepsTheExistingCost() = runBlocking {
        val (main, twin, _) = seedMergeFamily()
        val business = BUSINESS_ID.value
        val stock = database.inventoryDao()
        val source = stock.listBalancesForProduct(business, twin.productId.value).single()
        assertEquals(1, stock.updateBalanceIfVersion(
            business, source.productId, source.locationId, source.version, "0", "0.00", "PEN", source.updatedAt + 1,
        ))
        val location = UUID.randomUUID().toString()
        database.inventoryLocationDao().insert(InventoryLocationEntity(location, business, "Reserva", 1, 1))
        stock.insertBalanceIfAbsent(source.copy(locationId = location, quantityOnHand = "0"))
        val before = stock.listBalancesForProduct(business, main.productId.value)
        val positions = stock.listBalancesForProduct(business, twin.productId.value)
        val marker = positions.minBy { it.locationId }
        val key = "product-merge:v1:${twin.productId.value}:${main.productId.value}:${marker.locationId}:${marker.version}"

        assertTrue(products.mergeScannerGroupIfSafe(BUSINESS_ID, readMergeGroup(main, twin), main.productId))

        val movements = stock.listMovementsByIdempotencyPattern("product-merge:v1:%")
        assertEquals(2, movements.size)
        assertTrue(movements.all { it.productId == twin.productId.value && it.locationId == marker.locationId })
        assertEquals("1", movements.single { it.idempotencyKey == "$key:link" }.quantityDelta)
        assertEquals("-1", movements.single { it.idempotencyKey == "$key:out" }.quantityDelta)
        assertEquals(before, stock.listBalancesForProduct(business, main.productId.value))
    }

    @Test
    fun negativeAndZeroCostTransfersPreserveExistingAverageCostsByLocation() = runBlocking {
        val (main, twin, _) = seedMergeFamily()
        val business = BUSINESS_ID.value
        val stock = database.inventoryDao()
        val source = stock.listBalancesForProduct(business, twin.productId.value).single()
        assertEquals(1, stock.updateBalanceIfVersion(
            business, source.productId, source.locationId, source.version, "-3", "4", "PEN", source.updatedAt + 1,
        ))
        val location = UUID.randomUUID().toString()
        database.inventoryLocationDao().insert(InventoryLocationEntity(location, business, "Reserva", 1, 1))
        stock.insertBalanceIfAbsent(source.copy(locationId = location, quantityOnHand = "2", averageUnitCost = "0.00"))
        stock.insertBalanceIfAbsent(source.copy(productId = main.productId.value, locationId = location, quantityOnHand = "1", averageUnitCost = "9"))

        assertTrue(products.mergeScannerGroupByChoice(BUSINESS_ID, readMergeGroup(main, twin), main.productId))

        val target = stock.listBalancesForProduct(business, main.productId.value).associateBy { it.locationId }
        assertEquals("-1", target.getValue(source.locationId).quantityOnHand)
        assertEquals("2", target.getValue(source.locationId).averageUnitCost)
        assertEquals("3", target.getValue(location).quantityOnHand)
        assertEquals("9", target.getValue(location).averageUnitCost)
        val zeroCostAdjustments = stock.listMovementsByIdempotencyPattern("product-merge:v1:%").filter { it.locationId == location }
        assertEquals(2, zeroCostAdjustments.size)
        assertTrue(zeroCostAdjustments.all { it.unitCost == null })
    }

    @Test
    fun failedScannerMergeRollsBackAllInventoryWritesAndCanRetryChoosingTheOtherProduct() = runBlocking {
        val (main, twin, group) = seedMergeFamily()
        val stock = database.inventoryDao()
        val before = stock.listBalancesForBusiness(BUSINESS_ID.value)
        val beforeOutbox = database.outboxOperationDao().observeForBusiness(BUSINESS_ID.value).first().size
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_test_merge BEFORE UPDATE OF status ON products " +
                "WHEN NEW.status = 'ARCHIVED' BEGIN SELECT RAISE(ABORT, 'forced rollback'); END",
        )

        assertTrue(runCatching { products.mergeScannerGroupByChoice(BUSINESS_ID, group, twin.productId) }.isFailure)
        assertEquals(before, stock.listBalancesForBusiness(BUSINESS_ID.value))
        assertTrue(stock.listMovementsByIdempotencyPattern("product-merge:v1:%").isEmpty())
        assertEquals(main, products.findById(main.productId))
        assertEquals(twin, products.findById(twin.productId))
        assertEquals(beforeOutbox, database.outboxOperationDao().observeForBusiness(BUSINESS_ID.value).first().size)

        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_test_merge")
        assertTrue(products.mergeScannerGroupByChoice(BUSINESS_ID, group, twin.productId))
        assertEquals(main.barcode, products.findById(twin.productId)?.barcode)
        assertNull(products.findById(main.productId)?.barcode)
        assertEquals("ARCHIVED", products.findById(main.productId)?.status?.name)
        assertEquals("5", stock.listBalancesForProduct(BUSINESS_ID.value, twin.productId.value).single().quantityOnHand)
    }

    private suspend fun seedMergeFamily(): Triple<Product, Product, BarcodeGroup> {
        val main = products.create(product(3, sku = null, barcode = "7862225633578"))
        val twin = products.create(product(4, sku = null, barcode = "762225633578"))
        val location = UUID.randomUUID().toString()
        val business = BUSINESS_ID.value
        database.inventoryLocationDao().insert(InventoryLocationEntity(location, business, "Principal", 1, 1))
        val stock = database.inventoryDao()
        stock.insertBalanceIfAbsent(InventoryBalanceEntity(business, main.productId.value, location, "2", "2", "PEN", 0, 1))
        stock.insertBalanceIfAbsent(InventoryBalanceEntity(business, twin.productId.value, location, "3", "4", "PEN", 0, 1))
        return Triple(main, twin, readMergeGroup(main, twin))
    }

    private suspend fun readMergeGroup(main: Product, twin: Product): BarcodeGroup =
        requireNotNull(products.scannerCatalogDuplicateGroup(
            BUSINESS_ID, setOf(main.productId, twin.productId), products.listScannerIdentityCandidates(BUSINESS_ID), "76222563357",
        ))

    @Test
    fun barcodeSupersequenceQueryMatchesKotlinSubsequenceFilter() =
        runBlocking {
            listOf(
                product(1, sku = null, barcode = "7753176004930"),
                product(2, sku = null, barcode = "77531760049301"),
                product(3, sku = null, barcode = "7753176004931"),
                product(4, sku = null, barcode = "1753176004930"),
                product(5, sku = null, barcode = "7531760049300"),
                product(6, sku = null, barcode = "ZUKO753176004930"),
                product(7, sku = null, barcode = "75317600493"),
                product(8, sku = "753176004930", barcode = null),
            ).forEach { products.create(it) }
            products.create(product(9, sku = null, barcode = "7753176004930", business = OTHER_BUSINESS_ID))

            val full = products.listForBusiness(BUSINESS_ID)
            for (scanned in listOf("753176004930", "75317600493", "7531760", "000000", "9")) {
                val min = scanned.length + 1
                val max = scanned.length + 2
                // Mismo criterio que la implementación por defecto: largo acotado y subsecuencia.
                val expected =
                    full.filter { product ->
                        val stored = product.barcode ?: return@filter false
                        stored.length in min..max && isSubsequence(stored, scanned)
                    }
                assertEquals(
                    "lectura $scanned",
                    expected.ids(),
                    products.listBarcodeSupersequences(BUSINESS_ID, scanned, min, max).ids(),
                )
            }
        }

    private fun isSubsequence(
        text: String,
        needle: String,
    ): Boolean {
        var matched = 0
        text.forEach { if (matched < needle.length && it == needle[matched]) matched++ }
        return matched == needle.length
    }

    private fun List<Product>.ids(): Set<ProductId> = map { it.productId }.toSortedSet(compareBy { it.value })

    private fun product(
        seed: Int,
        sku: String?,
        barcode: String?,
        business: BusinessId = BUSINESS_ID,
    ) = Product(
        productId = ProductId.from(UUID.fromString("00000000-0000-0000-0000-%012d".format(100 + seed))),
        businessId = business,
        unitId = if (business == BUSINESS_ID) UNIT_ID else OTHER_UNIT_ID,
        name = "Producto $seed",
        sku = sku,
        barcode = barcode,
        locationId = null,
        purchaseUnitId = null,
        purchaseFactor = null,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private companion object {
        val BUSINESS_ID: BusinessId = BusinessId.from(UUID.fromString("00000000-0000-0000-0000-000000000911"))
        val OTHER_BUSINESS_ID: BusinessId = BusinessId.from(UUID.fromString("00000000-0000-0000-0000-000000000912"))
        val UNIT_ID: UnitId = UnitId.from(UUID.fromString("00000000-0000-0000-0000-000000000921"))
        val OTHER_UNIT_ID: UnitId = UnitId.from(UUID.fromString("00000000-0000-0000-0000-000000000922"))
    }
}
