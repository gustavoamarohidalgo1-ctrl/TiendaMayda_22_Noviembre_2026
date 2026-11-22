package com.facturastock.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.entity.BusinessEntity
import com.facturastock.app.data.local.entity.InventoryBalanceEntity
import com.facturastock.app.data.local.entity.InvoiceDraftEntity
import com.facturastock.app.data.local.entity.InventoryLocationEntity
import com.facturastock.app.data.local.entity.ProductEntity
import com.facturastock.app.data.local.entity.PurchaseEntity
import com.facturastock.app.data.local.entity.PurchaseLineEntity
import com.facturastock.app.data.local.entity.StockMovementEntity
import com.facturastock.app.data.local.entity.SupplierEntity
import com.facturastock.app.data.local.entity.UnitEntity
import com.facturastock.app.domain.model.InventoryDataAlert
import com.facturastock.app.domain.model.InventoryDiagnosticIssue
import com.facturastock.app.domain.model.InventoryProductDetail
import com.facturastock.app.domain.model.InventoryReadItem
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/** Fixtures de lectura sintéticos: verifica SQL y emisiones, sin depender de tiempos de benchmark. */
@RunWith(AndroidJUnit4::class)
class RoomInventoryProductReadTest {
    private lateinit var database: FacturaStockDatabase
    private lateinit var repository: RoomInventoryReadRepository
    private val queries = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() =
        runBlocking {
            database =
                Room
                    .inMemoryDatabaseBuilder(
                        ApplicationProvider.getApplicationContext<Context>(),
                        FacturaStockDatabase::class.java,
                    ).setQueryCallback(
                        object : RoomDatabase.QueryCallback {
                            override fun onQuery(
                                sqlQuery: String,
                                bindArgs: List<Any?>,
                            ) {
                                queries += sqlQuery
                            }
                        },
                        Executor { it.run() },
                    ).build()
            repository = RoomInventoryReadRepository(database, AppClock { Instant.EPOCH }, testDispatchers)
            database.withTransaction {
                database.businessDao().insert(BusinessEntity(BUSINESS.value, "Principal", 1L, 1L))
                database.businessDao().insert(BusinessEntity(OTHER_BUSINESS.value, "Otro", 1L, 1L))
                database.unitDao().insert(unit())
                database.inventoryLocationDao().insert(location())
                database.inventoryLocationDao().insert(location(SECOND_LOCATION, "Reserva", 100L))
                database.productDao().insert(product())
                database.productDao().insert(product(EMPTY_PRODUCT))
                database.inventoryDao().insertBalanceIfAbsent(balance())
            }
            queries.clear()
        }

    @After
    fun tearDown() = database.close()

    @Test
    fun diagnosticKeepsPositionChronologyAndAlertsAcrossProductsAndLocations() =
        runBlocking {
            val early = movement(9).copy(occurredAt = 2L, createdAt = 3L)
            val tiedFirst = movement(1).copy(occurredAt = 2L, createdAt = 4L)
            val tiedSecond = movement(2).copy(occurredAt = 2L, createdAt = 4L)
            val reserve = movement(7).copy(locationId = SECOND_LOCATION)
            val otherProduct = movement(8).copy(productId = EMPTY_PRODUCT.value, occurredAt = 0L)
            val dao = database.inventoryDao()
            dao.insertMovements(listOf(tiedSecond, otherProduct, reserve, tiedFirst, early))

            val rows = dao.listDiagnosticMovements(BUSINESS.value)
            assertEquals(
                listOf(reserve, early, tiedFirst, tiedSecond, otherProduct).map { it.movementId },
                rows.map { it.movementId },
            )

            val report = repository.diagnose(BUSINESS)
            assertEquals(5, report.movementCount)
            assertEquals(
                listOf(PRODUCT to LOCATION, PRODUCT to SECOND_LOCATION, EMPTY_PRODUCT to LOCATION),
                report.positions.map { it.productId to it.locationId.value },
            )
            val main = report.positions.first()
            assertEquals(BigDecimal("3"), main.ledgerQuantity)
            assertTrue(InventoryDiagnosticIssue.ORDER_AMBIGUOUS in main.issues)
            assertFalse(InventoryDiagnosticIssue.QUANTITY_DIVERGENCE in main.issues)
            assertTrue(report.positions.drop(1).all {
                it.ledgerQuantity == BigDecimal.ONE && InventoryDiagnosticIssue.MISSING_CACHED_BALANCE in it.issues
            })
        }

    @Test
    fun itemMatchesFullDetailWithMultipleLocationsAnd1500MovementsWithoutReadingHistory() =
        runBlocking {
            database.withTransaction {
                database.inventoryDao().insertBalanceIfAbsent(
                    balance().copy(
                        locationId = SECOND_LOCATION,
                        quantityOnHand = "-0.000000000000000000000000000000000001",
                        averageUnitCost = "123456.123456789012345678123456789012345678",
                        currencyCode = "USD",
                    ),
                )
                database.inventoryLocationDao().update(location(SECOND_LOCATION, "Reserva", 100L).copy(status = "ARCHIVED"))
                database.inventoryDao().insertMovements((0 until 1_500).map(::movement))
            }
            val detail = requireNotNull(repository.observeProduct(BUSINESS, PRODUCT).first())
            assertEquals(1_500, detail.movements.size)
            queries.clear()

            val item = requireNotNull(repository.observeProductItem(BUSINESS, PRODUCT).first())
            assertEquals(detail.item, item)
            assertEquals(2, item.positions.size)
            assertTrue(InventoryDataAlert.NEGATIVE_STOCK in item.allAlerts)
            assertTrue(InventoryDataAlert.MIXED_CURRENCIES in item.allAlerts)
            assertTrue(InventoryDataAlert.ARCHIVED_LOCATION in item.allAlerts)
            val itemReads = queries.filter { it.startsWith("SELECT p.businessId") }
            assertEquals(1, itemReads.size)
            assertFalse(queries.any { it.startsWith("SELECT") && it.contains("stock_movements") })
            assertFalse(queries.any { it.startsWith("SELECT") && it.contains("FROM purchases") })
            database.openHelper.readableDatabase
                .query(
                    "EXPLAIN QUERY PLAN ${itemReads.single()}",
                    arrayOf<Any>(BUSINESS.value, PRODUCT.value),
                ).use { cursor ->
                    val plan = buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }
                    assertTrue(plan.any { it.contains("SEARCH") && it.contains("sqlite_autoindex_products_1") })
                    assertFalse(plan.any { it.contains("SCAN p") || it.contains("SCAN b") })
                }
        }

    @Test
    fun movementWindowsMatchFullHistoryInOrderAcrossTiedTimestamps() =
        runBlocking {
            val rows = (0 until 151).reversed().map { index ->
                movement(index).copy(occurredAt = index % 3L, createdAt = index % 2L)
            }
            database.inventoryDao().insertMovements(rows)
            val complete = requireNotNull(repository.observeProduct(BUSINESS, PRODUCT).first())
            assertEquals(151, complete.totalMovementCount)
            for (limit in listOf(1, 50, 100, 151, 200)) {
                queries.clear()
                val window = requireNotNull(repository.observeProductWindow(BUSINESS, PRODUCT, limit).first())
                assertEquals(151, window.totalMovementCount)
                assertEquals(complete.movements.take(limit), window.movements)
                assertEquals(complete.item, window.item)
                val reads = queries.filter { it.startsWith("SELECT m.movementId") }
                assertTrue(reads.single().contains("LIMIT ?"))
            }
            val empty = requireNotNull(repository.observeProductWindow(BUSINESS, EMPTY_PRODUCT, 50).first())
            assertEquals(0, empty.totalMovementCount)
            assertTrue(empty.movements.isEmpty())
            assertNull(repository.observeProductWindow(OTHER_BUSINESS, PRODUCT, 50).first())
        }

    @Test
    fun boundedWindowRefreshesCountAndRowsForBackdatedMovementsAndPurchaseChanges() =
        runBlocking {
            val purchaseId = seedPurchaseMovement()
            database.inventoryDao().insertMovements(listOf(movement(4)))
            val emissions = Channel<InventoryProductDetail?>(Channel.UNLIMITED)
            val collector = launch {
                repository.observeProductWindow(BUSINESS, PRODUCT, 1).distinctUntilChanged().collect(emissions::send)
            }
            try {
                val initial = emissions.nextDetail()
                assertEquals(2, initial.totalMovementCount)
                assertEquals("F001-1", initial.movements.single().purchaseDocumentNumber)
                database.withTransaction {
                    database.openHelper.writableDatabase.execSQL(
                        "UPDATE purchases SET documentNumber = '2' WHERE purchaseId = ?",
                        arrayOf(purchaseId),
                    )
                }
                assertEquals("F001-2", emissions.nextDetail().movements.single().purchaseDocumentNumber)

                val backdated = movement(5).copy(occurredAt = 0L)
                database.inventoryDao().insertMovements(listOf(backdated))
                val updated = emissions.nextDetail()
                assertEquals(3, updated.totalMovementCount)
                assertEquals(backdated.movementId, updated.movements.single().movementId)
            } finally {
                collector.cancelAndJoin()
                emissions.close()
            }
        }

    @Test
    fun itemPreservesEmptyProductsMissingIdsAndTenantBoundary() =
        runBlocking {
            val empty = requireNotNull(repository.observeProductItem(BUSINESS, EMPTY_PRODUCT).first())
            assertEquals(repository.observeProduct(BUSINESS, EMPTY_PRODUCT).first()?.item, empty)
            assertTrue(empty.positions.isEmpty())
            assertNull(repository.observeProductItem(OTHER_BUSINESS, PRODUCT).first())
            assertNull(repository.observeProductItem(BUSINESS, ProductId.from(UUID(0L, 999L))).first())
        }

    @Test
    fun itemEmitsBalanceUnitLocationAndProductChangesWithoutLoadingMovements() =
        runBlocking {
            val emissions = Channel<InventoryReadItem?>(Channel.UNLIMITED)
            val collector = launch { repository.observeProductItem(BUSINESS, PRODUCT).collect(emissions::send) }
            try {
                assertEquals(BigDecimal("3"), emissions.nextItem().totalQuantityOnHand)
                queries.clear()
                // Room invalida toda la tabla; el producto ajeno y una escritura idéntica no deben emitirse.
                database.unitDao().update(unit())
                database.inventoryDao().insertBalanceIfAbsent(balance().copy(productId = EMPTY_PRODUCT.value))
                assertEquals(
                    1,
                    database.inventoryDao().updateBalanceIfVersion(
                        BUSINESS.value,
                        PRODUCT.value,
                        LOCATION,
                        0L,
                        "2",
                        "4.125",
                        "PEN",
                        1L,
                    ),
                )
                val changedBalance = emissions.nextItem()
                assertEquals(BigDecimal("2"), changedBalance.totalQuantityOnHand)
                assertEquals(
                    BigDecimal("4.125"),
                    changedBalance.positions
                        .single()
                        .averageUnitCost.amount,
                )

                // Los timestamps son deliberadamente iguales: no sirven como firma del contenido.
                database.unitDao().update(unit().copy(symbol = "ud"))
                assertEquals("ud", emissions.nextItem().unitSymbol)
                database.inventoryLocationDao().update(location().copy(name = "Mostrador", status = "ARCHIVED"))
                val changedLocation = emissions.nextItem()
                assertEquals("Mostrador", changedLocation.positions.single().locationName)
                assertTrue(InventoryDataAlert.ARCHIVED_LOCATION in changedLocation.allAlerts)

                assertEquals(
                    1,
                    database.productDao().update(
                        productId = PRODUCT.value,
                        unitId = UNIT,
                        name = "Actualizado",
                        normalizedName = "actualizado",
                        locationId = LOCATION,
                        sku = "NUEVO",
                        barcode = null,
                        purchaseUnitId = null,
                        purchaseFactor = null,
                        salePriceMinorUnits = null,
                        salePriceCurrencyCode = null,
                        status = "ARCHIVED",
                        expectedVersion = 1L,
                        updatedAt = 1L,
                    ),
                )
                val changedProduct = emissions.nextItem()
                assertEquals("Actualizado", changedProduct.productName)
                assertEquals("NUEVO", changedProduct.sku)
                assertTrue(InventoryDataAlert.ARCHIVED_PRODUCT in changedProduct.allAlerts)
                assertFalse(queries.any { it.startsWith("SELECT") && it.contains("stock_movements") })
            } finally {
                collector.cancelAndJoin()
                emissions.close()
            }
        }

    @Test
    fun fullDetailStillEmitsChangedContentsWhenCountsAndTimestampsAreIdentical() =
        runBlocking {
            database.inventoryDao().insertBalanceIfAbsent(balance().copy(locationId = SECOND_LOCATION))
            database.inventoryDao().insertMovements(listOf(movement(0)))
            val emissions = Channel<InventoryProductDetail?>(Channel.UNLIMITED)
            val collector = launch { repository.observeProduct(BUSINESS, PRODUCT).distinctUntilChanged().collect(emissions::send) }
            try {
                assertEquals(1, emissions.nextDetail().movements.size)
                database.unitDao().update(unit())
                database.inventoryDao().insertBalanceIfAbsent(balance().copy(productId = EMPTY_PRODUCT.value))
                database.unitDao().update(unit().copy(symbol = "ud"))
                assertEquals("ud", emissions.nextDetail().item.unitSymbol)

                database.inventoryLocationDao().update(location().copy(name = "Mostrador"))
                val locationChanged = emissions.nextDetail()
                assertEquals("Mostrador", locationChanged.movements.single().locationName)
                assertEquals(
                    "Mostrador",
                    locationChanged.item.positions
                        .first { it.locationId == LocationId.parse(LOCATION) }
                        .locationName,
                )

                database.inventoryDao().updateBalanceIfVersion(BUSINESS.value, PRODUCT.value, LOCATION, 0L, "2", "3.5", "PEN", 1L)
                assertEquals(BigDecimal("5"), emissions.nextDetail().item.totalQuantityOnHand)

                database.inventoryDao().insertMovements(listOf(movement(1)))
                assertEquals(2, emissions.nextDetail().movements.size)
            } finally {
                collector.cancelAndJoin()
                emissions.close()
            }
        }

    private suspend fun Channel<InventoryReadItem?>.nextItem() = requireNotNull(withTimeout(5_000) { receive() })

    private suspend fun Channel<InventoryProductDetail?>.nextDetail() = requireNotNull(withTimeout(5_000) { receive() })

    private suspend fun seedPurchaseMovement(): String {
        val draftId = UUID(30L, 1L).toString()
        val supplierId = UUID(30L, 2L).toString()
        val purchaseId = UUID(30L, 3L).toString()
        val lineId = UUID(30L, 4L).toString()
        database.withTransaction {
            database.invoiceDraftDao().insert(InvoiceDraftEntity(draftId, BUSINESS.value, 1L, 1L))
            database.supplierDao().insert(SupplierEntity(supplierId, BUSINESS.value, "Proveedor", 1L, 1L))
            database.purchaseDao().insert(
                PurchaseEntity(
                    purchaseId = purchaseId, businessId = BUSINESS.value, sourceDraftId = draftId,
                    supplierId = supplierId, documentType = "INVOICE", documentSeries = "F001",
                    documentNumber = "1", issueDate = "2026-01-01", currencyCode = "PEN",
                    subtotalMinorUnits = 100L, taxMinorUnits = 0L, otherChargesMinorUnits = 0L,
                    totalMinorUnits = 100L, idempotencyKey = "test-purchase", createdAt = 1L, updatedAt = 1L,
                ),
            )
            database.purchaseLineDao().insertAll(
                listOf(
                    PurchaseLineEntity(
                        purchaseLineId = lineId, purchaseId = purchaseId, productId = PRODUCT.value, unitId = UNIT,
                        position = 0, rawText = "Producto", description = "Producto", quantity = "1",
                        readUnitCost = "1", currencyCode = "PEN", taxMinorUnits = 0L, totalMinorUnits = 100L,
                    ),
                ),
            )
            database.inventoryDao().insertMovements(
                listOf(movement(0).copy(type = "PURCHASE", purchaseId = purchaseId, purchaseLineId = lineId)),
            )
        }
        return purchaseId
    }

    private fun unit() = UnitEntity(UNIT, BUSINESS.value, "NIU", "Unidad", 1L, 1L)

    private fun location(
        id: String = LOCATION,
        name: String = "Principal",
        updatedAt: Long = 1L,
    ) = InventoryLocationEntity(id, BUSINESS.value, name, 1L, updatedAt)

    private fun product(id: ProductId = PRODUCT) = ProductEntity(id.value, BUSINESS.value, UNIT, "Producto ${id.value}", 1L, 1L)

    private fun balance() = InventoryBalanceEntity(BUSINESS.value, PRODUCT.value, LOCATION, "3", "3.5", "PEN", 0L, 1L)

    private fun movement(index: Int) =
        StockMovementEntity(
            movementId = UUID(10L, index.toLong()).toString(),
            businessId = BUSINESS.value,
            productId = PRODUCT.value,
            locationId = LOCATION,
            type = "ADJUSTMENT",
            quantityDelta = "1",
            unitCost = "3.5",
            currencyCode = "PEN",
            idempotencyKey = "read-$index",
            occurredAt = 1L,
            createdAt = 1L,
        )

    private companion object {
        val BUSINESS = BusinessId.from(UUID(0L, 1L))
        val OTHER_BUSINESS = BusinessId.from(UUID(0L, 2L))
        val PRODUCT = ProductId.from(UUID(0L, 3L))
        val EMPTY_PRODUCT = ProductId.from(UUID(0L, 4L))
        val UNIT = UUID(0L, 5L).toString()
        val LOCATION = UUID(0L, 6L).toString()
        val SECOND_LOCATION = UUID(0L, 7L).toString()
    }
}
