package com.facturastock.app.feature.sales

import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.ProductRepository
import com.facturastock.app.domain.usecase.BarcodeFamilies
import com.facturastock.app.domain.usecase.BarcodeGroup
import com.facturastock.app.domain.usecase.PositionalBarcodeResult
import com.facturastock.app.domain.usecase.ScannerDuplicateNames
import com.facturastock.app.domain.usecase.resolvePositionalBarcode
import kotlinx.coroutines.CancellationException

/** Orden de las fichas que Vender puede usar automáticamente para esta lectura. */
internal suspend fun resolveSalesBarcodeFamily(
    products: ProductRepository,
    businessId: BusinessId,
    scanned: String,
    catalog: List<Product>,
    exact: Product?,
    competitors: List<Product>,
): List<Product> {
    val matches = if (exact != null) {
        listOf(exact) + competitors
    } else {
        when (val result = resolvePositionalBarcode(scanned, businessId, catalog)) {
            is PositionalBarcodeResult.Found -> listOf(result.product)
            is PositionalBarcodeResult.Ambiguous -> result.products
            else -> return emptyList()
        }
    }
    val eligible = matches.filter { it.businessId == businessId && (exact != null || it.status == CatalogStatus.ACTIVE) }
    if (eligible.isEmpty()) return emptyList()
    val candidateIds = eligible.mapTo(mutableSetOf()) { it.productId }
    val group = optionalBarcodeHistory {
        // Un fallo leyendo familias aún permite utilizar la coincidencia exacta de barras.
        val groupCatalog = if (exact == null) catalog else products.listScannerIdentityCandidates(businessId)
        products.scannerDuplicateGroup(businessId, candidateIds, groupCatalog)
    }
    val alternatives = group?.eligibleSaleAlternatives().orEmpty()
    if (exact != null) {
        if (group != null) return alternatives
        return listOfNotNull(exact.takeIf { it.status == CatalogStatus.ACTIVE && it.barcode == scanned })
    }
    val activity = if (group == null) {
        optionalBarcodeHistory { products.scannerSalesActivity(businessId, candidateIds) }.orEmpty()
    } else emptyMap()
    val preferred = BarcodeFamilies.choose(scanned, eligible, group, activity) ?: return emptyList()
    val excluded = group?.products?.mapTo(mutableSetOf()) { it.productId } ?: setOf(preferred.productId)
    if (BarcodeFamilies.hasCompetitor(scanned, businessId, catalog, excluded)) return emptyList()
    return listOf(preferred) + alternatives.filter { it.productId != preferred.productId }
}

private fun BarcodeGroup.eligibleSaleAlternatives(): List<Product> {
    val reference = products.firstOrNull { it.productId == keeper } ?: products.firstOrNull() ?: return emptyList()
    return listOf(reference) + products.filter {
        it.productId != reference.productId && ScannerDuplicateNames.areRelated(it.name, reference.name)
    }
}

// En el lector original estas dos consultas aportan una preferencia opcional.
private suspend fun <T> optionalBarcodeHistory(read: suspend () -> T): T? =
    try {
        read()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
