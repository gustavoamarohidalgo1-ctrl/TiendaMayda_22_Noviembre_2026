package com.facturastock.app.data.repository

import androidx.room.withTransaction
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.dao.InventoryDao
import com.facturastock.app.data.local.entity.InventoryBalanceEntity
import com.facturastock.app.data.local.entity.ProductEntity
import com.facturastock.app.data.local.entity.StockMovementEntity
import com.facturastock.app.data.local.mapper.toDomain
import com.facturastock.app.domain.model.InventoryAverageCostRequest
import com.facturastock.app.domain.model.InventoryAverageCostResult
import com.facturastock.app.domain.model.InventoryCostRoundingPolicy
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.usecase.BarcodeFamily
import com.facturastock.app.domain.usecase.InventoryCostingService
import com.facturastock.app.domain.usecase.isPositionalBarcode
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/** El repositorio revalida la elección y sus permisos en la misma transacción. */
internal suspend fun FacturaStockDatabase.mergeScannerFamily(
    businessId: BusinessId,
    family: BarcodeFamily,
    keeperId: ProductId,
    nowMillis: Long,
    writeProduct: suspend (ProductEntity) -> Unit,
): Product? = withTransaction {
    val business = businessId.value
    if (businessDao().findById(business)?.status != "ACTIVE" || cloudBusinessBindingDao().findByLocal(business) != null) {
        return@withTransaction null
    }
    val full = family.main.barcode ?: return@withTransaction null
    if (family.twins.isEmpty() || family.twins.any { !isPositionalBarcode(it.barcode ?: "", full) }) return@withTransaction null
    val expected = family.products
    val ids = expected.map { it.productId.value }
    if (ids.size != ids.distinct().size) return@withTransaction null
    val products = productDao()
    val rows = ids.chunked(500).flatMap { products.findByIds(it) }.associateBy { it.productId }
    if (expected.any { it.businessId != businessId || rows[it.productId.value]?.toDomain() != it }) return@withTransaction null
    val keeper = rows[keeperId.value] ?: return@withTransaction null
    val discarded = ids.filter { it != keeper.productId }
    if (discarded.chunked(500).any { saleDao().hasScannerDraftLine(business, it) }) return@withTransaction null

    val inventory = inventoryDao()
    val balances = ids.associateWith { inventory.listBalancesForProduct(business, it) }
    val prior = balances.values.flatten()
    val targetBalances = balances.getValue(keeper.productId).associateBy { it.locationId }.toMutableMap()
    val currencies = targetBalances.mapValues { it.value.currencyCode }.toMutableMap()
    val sources = discarded.flatMap { balances.getValue(it) }
    for (source in sources) {
        if (source.quantityOnHand.toBigDecimal().signum() == 0) continue
        if (currencies.getOrPut(source.locationId) { source.currencyCode } != source.currencyCode) return@withTransaction null
    }

    // Se escriben los movimientos después de los anteriores, incluso con el reloj local atrasado.
    var timestamp = maxOf(nowMillis, rows.values.maxOf { it.updatedAt }, prior.maxOfOrNull { it.updatedAt } ?: 0L)
    for (source in sources) {
        for (id in listOf(source.productId, keeper.productId)) {
            val last = inventory.latestMovementTimestamp(business, id, source.locationId)
            if (last != null) timestamp = maxOf(timestamp, last)
        }
    }
    fun nextTimestamp(): Long = Math.addExact(timestamp, 1L).also { timestamp = it }
    val aliases = supplierProductAliasDao()
    for (id in discarded) {
        val positions = balances.getValue(id)
        val nonzero = positions.filter { it.quantityOnHand.toBigDecimal().signum() != 0 }
        for (source in nonzero) {
            val quantity = source.quantityOnHand.toBigDecimal()
            val current = targetBalances[source.locationId]
            val key = source.mergeKey(keeper.productId)
            val outAt = nextTimestamp()
            inventory.writeMergeBalance(source.copy(quantityOnHand = "0", updatedAt = outAt), source)
            val inAt = nextTimestamp()
            val target = InventoryBalanceEntity(
                businessId = business, productId = keeper.productId, locationId = source.locationId,
                quantityOnHand = ((current?.quantityOnHand?.toBigDecimal() ?: BigDecimal.ZERO) + quantity).toPlainString(),
                averageUnitCost = mergedAverageCost(current, source, quantity), currencyCode = source.currencyCode,
                version = if (current == null) 0L else Math.addExact(current.version, 1L), updatedAt = inAt,
            )
            inventory.writeMergeBalance(target, current)
            targetBalances[source.locationId] = target
            inventory.insertMovements(listOf(
                source.mergeMovement(id, quantity.negate(), "$key:out", outAt),
                source.mergeMovement(keeper.productId, quantity, "$key:in", inAt),
            ))
        }
        // Para una ficha agotada con una posición existente el lector registraba el enlace con
        // dos ajustes opuestos sobre la misma ficha. Su saldo y la suma del libro se conservan.
        val markerPosition = if (nonzero.isEmpty()) positions.minByOrNull { it.locationId } else null
        if (markerPosition != null) {
            val key = markerPosition.mergeKey(keeper.productId)
            val linkAt = nextTimestamp()
            inventory.writeMergeBalance(markerPosition.copy(updatedAt = linkAt), markerPosition)
            inventory.insertMovements(listOf(
                markerPosition.mergeMovement(id, BigDecimal.ONE, "$key:link", linkAt),
                markerPosition.mergeMovement(id, BigDecimal.ONE.negate(), "$key:out", nextTimestamp()),
            ))
        }
        val discardedProduct = rows.getValue(id)
        if (discardedProduct.status != "ARCHIVED" || discardedProduct.barcode != null) {
            writeProduct(discardedProduct.copy(status = "ARCHIVED", barcode = null, updatedAt = maxOf(nowMillis, discardedProduct.updatedAt)))
        }
        for (alias in aliases.listForProduct(id)) {
            if (alias.businessId == business) {
                check(aliases.update(alias.copy(productId = keeper.productId, updatedAt = maxOf(nowMillis, alias.updatedAt))) == 1)
            }
        }
    }
    if (keeper.status != "ACTIVE" || keeper.barcode != full) {
        writeProduct(keeper.copy(status = "ACTIVE", barcode = full, updatedAt = maxOf(nowMillis, keeper.updatedAt)))
    }
    val after = ids.flatMap { inventory.listBalancesForProduct(business, it) }
    check(after.filter { it.productId != keeper.productId }.all { it.quantityOnHand.toBigDecimal().signum() == 0 })
    check(mergeTotals(prior) == mergeTotals(after))
    checkNotNull(products.findById(keeper.productId)).toDomain()
}

private fun InventoryBalanceEntity.mergeKey(keeper: String): String =
    "product-merge:v1:$productId:$keeper:$locationId:$version"

private fun InventoryBalanceEntity.mergeMovement(id: String, delta: BigDecimal, key: String, at: Long) =
    StockMovementEntity(
        movementId = UUID.randomUUID().toString(), businessId = businessId, productId = id, locationId = locationId,
        type = "ADJUSTMENT", quantityDelta = delta.toPlainString(),
        unitCost = averageUnitCost.takeIf { it.toBigDecimal().signum() > 0 }, currencyCode = currencyCode,
        idempotencyKey = key, occurredAt = at, createdAt = at,
    )

private suspend fun InventoryDao.writeMergeBalance(balance: InventoryBalanceEntity, previous: InventoryBalanceEntity?) {
    if (previous == null) {
        check(insertBalanceIfAbsent(balance) != -1L)
    } else {
        check(updateBalanceIfVersion(
            balance.businessId, balance.productId, balance.locationId, previous.version, balance.quantityOnHand,
            balance.averageUnitCost, balance.currencyCode, balance.updatedAt,
        ) == 1)
    }
}

private fun mergedAverageCost(current: InventoryBalanceEntity?, source: InventoryBalanceEntity, quantity: BigDecimal): String {
    val incomingCost = source.averageUnitCost.toBigDecimal()
    if (current == null) return if (incomingCost.signum() > 0) incomingCost.toPlainString() else "0"
    if (quantity.signum() <= 0 || incomingCost.signum() == 0) return current.averageUnitCost
    val result = InventoryCostingService().calculateAverage(InventoryAverageCostRequest(
        previousQuantity = current.quantityOnHand.toBigDecimal(), previousAverageUnitCost = current.averageUnitCost.toBigDecimal(),
        incomingInventoryQuantity = quantity, incomingAppliedCostTotal = incomingCost * quantity,
        roundingPolicy = InventoryCostRoundingPolicy(18, RoundingMode.HALF_EVEN),
    ))
    check(result is InventoryAverageCostResult.Calculated)
    return result.calculation.resultingAverageUnitCost.toPlainString()
}

private fun mergeTotals(balances: List<InventoryBalanceEntity>): Map<Pair<String, String>, BigDecimal> =
    balances.groupBy { it.locationId to it.currencyCode }.mapValues { (_, rows) ->
        rows.sumOf { it.quantityOnHand.toBigDecimal() }.stripTrailingZeros()
    }
