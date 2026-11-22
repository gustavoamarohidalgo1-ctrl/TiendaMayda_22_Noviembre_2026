package com.facturastock.app.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facturastock.app.core.coroutines.DefaultDispatcherProvider
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.entity.BusinessEntity
import com.facturastock.app.data.local.entity.InvoiceDraftEntity
import com.facturastock.app.data.local.entity.OutboxOperationEntity
import com.facturastock.app.data.local.entity.ProductEntity
import com.facturastock.app.data.local.entity.PurchaseEntity
import com.facturastock.app.data.local.entity.PurchaseLineEntity
import com.facturastock.app.data.local.entity.SupplierEntity
import com.facturastock.app.data.local.entity.UnitEntity
import com.facturastock.app.data.repository.RoomPurchaseReadRepository
import com.facturastock.app.domain.model.OutboxOperationStatus
import com.facturastock.app.domain.model.PurchaseHistoryRequest
import com.facturastock.app.domain.model.PurchaseProductProvenance
import com.facturastock.app.domain.model.PurchaseStatus
import com.facturastock.app.domain.model.id.BusinessId
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PurchaseHistoryPagingQueryTest {
    private lateinit var database: FacturaStockDatabase

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            FacturaStockDatabase::class.java,
        ).build()
        seedTenant(BUSINESS_A, SUPPLIER_A, "Proveedor Ágil")
        seedTenant(BUSINESS_B, SUPPLIER_B, "Proveedor ajeno")
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun keysetUsesCreatedAtAndPurchaseIdWithoutChangingTheCompleteTerminalOrder() = runBlocking {
        insertPurchase(seed = 1, businessId = BUSINESS_A, supplierId = SUPPLIER_A, createdAt = 2_000L)
        insertPurchase(
            seed = 2,
            businessId = BUSINESS_A,
            supplierId = SUPPLIER_A,
            createdAt = 2_000L,
            status = PurchaseStatus.VOIDED,
        )
        insertPurchase(seed = 3, businessId = BUSINESS_A, supplierId = SUPPLIER_A, createdAt = 1_000L)
        insertPurchase(seed = 4, businessId = BUSINESS_B, supplierId = SUPPLIER_B, createdAt = 3_000L)

        val firstPage = page(businessId = BUSINESS_A, limit = 2)
        assertEquals(listOf(uuid(2), uuid(1)), firstPage.map { row -> row.purchaseId })

        val secondPage = page(
            businessId = BUSINESS_A,
            beforeCreatedAt = firstPage.last().historyCreatedAt,
            beforePurchaseId = firstPage.last().purchaseId,
            limit = 2,
        )
        assertEquals(listOf(uuid(3)), secondPage.map { row -> row.purchaseId })

        val complete = database.purchaseDao().observeReadSummaries(BUSINESS_A).first()
        assertEquals(
            complete.map { row -> row.purchaseId },
            firstPage.plus(secondPage).map { row -> row.purchaseId },
        )
    }

    @Test
    fun statusAndLatestPurchaseOutboxAreFilteredBeforeTheLimit() = runBlocking {
        insertPurchase(seed = 10, businessId = BUSINESS_A, supplierId = SUPPLIER_A, createdAt = 3_000L)
        insertPurchase(
            seed = 11,
            businessId = BUSINESS_A,
            supplierId = SUPPLIER_A,
            createdAt = 2_000L,
            status = PurchaseStatus.VOIDED,
        )
        insertPurchase(seed = 12, businessId = BUSINESS_A, supplierId = SUPPLIER_A, createdAt = 1_000L)
        insertOutbox(seed = 100, purchaseSeed = 12, status = OutboxOperationStatus.PENDING, at = 1_100L)
        insertOutbox(seed = 101, purchaseSeed = 12, status = OutboxOperationStatus.FAILED, at = 1_200L)

        assertEquals(
            listOf(uuid(11)),
            page(
                businessId = BUSINESS_A,
                status = PurchaseStatus.VOIDED.name,
                limit = 1,
            ).map { row -> row.purchaseId },
        )
        assertEquals(
            listOf(uuid(12)),
            page(
                businessId = BUSINESS_A,
                syncStatus = OutboxOperationStatus.FAILED.name,
                limit = 1,
            ).map { row -> row.purchaseId },
        )
        assertEquals(
            listOf(uuid(10)),
            page(
                businessId = BUSINESS_A,
                syncStatus = "",
                limit = 1,
            ).map { row -> row.purchaseId },
        )
    }

    @Test
    fun pageCountsDistinctProductsWithinEachPurchaseIncludingRepeatedLines() = runBlocking {
        val unit = uuid(2_000)
        database.unitDao().insert(
            UnitEntity(
                unitId = unit,
                businessId = BUSINESS_A,
                code = "UND",
                name = "Unidad",
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        val product = uuid(3_000)
        database.productDao().insert(
            ProductEntity(
                productId = product,
                businessId = BUSINESS_A,
                unitId = unit,
                name = "Arroz",
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        for (seed in 1..3) {
            insertPurchase(seed, BUSINESS_A, SUPPLIER_A, createdAt = 2_000L)
        }
        val line = PurchaseLineEntity(
            purchaseLineId = uuid(4_000),
            purchaseId = uuid(2),
            productId = product,
            unitId = unit,
            position = 0,
            rawText = "Arroz",
            description = "Arroz",
            quantity = "1",
            readUnitCost = "1",
            currencyCode = "PEN",
            taxMinorUnits = 0,
            totalMinorUnits = 100,
            productProvenance = PurchaseProductProvenance.EXISTING.name,
        )
        database.purchaseLineDao().insertAll(
            listOf(
                line,
                line.copy(purchaseLineId = uuid(4_001), position = 1),
                line.copy(purchaseLineId = uuid(4_002), purchaseId = uuid(1)),
            ),
        )

        val summaries = page(BUSINESS_A, limit = 2)
        assertEquals(listOf(uuid(3), uuid(2)), summaries.map { it.purchaseId })
        assertEquals(listOf(0, 2), summaries.map { it.lineCount })
        assertEquals(listOf(0, 1), summaries.map { it.productCount })
        assertEquals(listOf(0, 1), summaries.map { it.existingProductCount })
    }

    @Test
    fun supplierRenameRefreshesAnOpenLiteralSearchWithoutAnotherPurchaseWrite() = runBlocking {
        insertPurchase(seed = 5, businessId = BUSINESS_A, supplierId = SUPPLIER_A, createdAt = 1_000L)
        val supplier = requireNotNull(database.supplierDao().findById(SUPPLIER_A))
        val repository = RoomPurchaseReadRepository(database, DefaultDispatcherProvider())
        val request = PurchaseHistoryRequest(query = " %_Ágil ", pageSize = 1, visiblePages = 1)

        val pages = withTimeout(10_000L) {
            repository.observePurchaseHistory(requireNotNull(BusinessId.parse(BUSINESS_A)), request)
                .distinctUntilChanged()
                .onEach { page ->
                    if (page.items.isEmpty()) {
                        assertEquals(
                            1,
                            database.supplierDao().updateCas(
                                supplier.copy(legalName = "Distribuidora %_ágil", updatedAt = 2_000L),
                            ),
                        )
                    }
                }
                .take(2)
                .toList()
        }

        assertEquals(emptyList<String>(), pages.first().items.map { it.purchaseId.value })
        assertEquals(listOf(uuid(5)), pages.last().items.map { it.purchaseId.value })
        assertEquals(false, pages.last().hasMore)
    }

    private suspend fun page(
        businessId: String,
        status: String? = null,
        syncStatus: String? = null,
        beforeCreatedAt: Long? = null,
        beforePurchaseId: String? = null,
        limit: Int,
    ): List<PurchaseReadSummaryRow> = database.purchaseDao().listReadSummaryPage(
        businessId = businessId,
        status = status,
        syncStatus = syncStatus,
        beforeCreatedAt = beforeCreatedAt,
        beforePurchaseId = beforePurchaseId,
        limit = limit,
    )

    private suspend fun seedTenant(businessId: String, supplierId: String, supplierName: String) {
        database.businessDao().insert(
            BusinessEntity(
                businessId = businessId,
                legalName = "Negocio $businessId",
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        database.supplierDao().insert(
            SupplierEntity(
                supplierId = supplierId,
                businessId = businessId,
                legalName = supplierName,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
    }

    private suspend fun insertPurchase(
        seed: Int,
        businessId: String,
        supplierId: String,
        createdAt: Long,
        status: PurchaseStatus = PurchaseStatus.POSTED,
    ) {
        val purchaseId = uuid(seed)
        val draftId = uuid(1_000 + seed)
        database.invoiceDraftDao().insert(
            InvoiceDraftEntity(
                draftId = draftId,
                businessId = businessId,
                supplierId = supplierId,
                supplierLegalNameNormalized = if (seed == 1) "Distribuidora Ágil" else null,
                createdAt = createdAt,
                updatedAt = createdAt,
            ),
        )
        database.purchaseDao().insert(
            PurchaseEntity(
                purchaseId = purchaseId,
                businessId = businessId,
                sourceDraftId = draftId,
                supplierId = supplierId,
                documentType = "INVOICE",
                documentSeries = "F001",
                documentNumber = "%08d".format(seed),
                issueDate = "2026-08-10",
                currencyCode = "PEN",
                subtotalMinorUnits = 1_000L,
                taxMinorUnits = 180L,
                otherChargesMinorUnits = 0L,
                totalMinorUnits = 1_180L,
                status = status.name,
                idempotencyKey = "history-$businessId-$seed",
                createdAt = createdAt,
                updatedAt = createdAt,
                postedAt = createdAt,
                voidedAt = createdAt.takeIf { status == PurchaseStatus.VOIDED },
            ),
        )
    }

    private suspend fun insertOutbox(
        seed: Int,
        purchaseSeed: Int,
        status: OutboxOperationStatus,
        at: Long,
    ) {
        database.outboxOperationDao().insert(
            OutboxOperationEntity(
                operationId = uuid(seed),
                businessId = BUSINESS_A,
                purchaseId = uuid(purchaseSeed),
                idempotencyKey = "history-outbox-$seed",
                operationType = "SYNC_PURCHASE",
                payload = "{}",
                status = status.name,
                createdAt = at,
                updatedAt = at,
                entityType = "PURCHASE",
                entityId = uuid(purchaseSeed),
            ),
        )
    }

    private companion object {
        val BUSINESS_A = uuid(8_001)
        val BUSINESS_B = uuid(8_002)
        val SUPPLIER_A = uuid(8_101)
        val SUPPLIER_B = uuid(8_102)

        fun uuid(seed: Int): String =
            "00000000-0000-0000-0000-%012d".format(seed)
    }
}
