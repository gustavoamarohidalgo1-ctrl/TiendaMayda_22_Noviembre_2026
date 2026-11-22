package com.facturastock.app.feature.common

import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.repository.ProductRepository
import com.facturastock.app.domain.usecase.BarcodeFamilies
import com.facturastock.app.domain.usecase.BarcodeGroup
import com.facturastock.app.domain.usecase.BarcodeSimilarity
import com.facturastock.app.domain.usecase.PositionalBarcodeResult
import com.facturastock.app.domain.usecase.canonicalGtinOrNull
import com.facturastock.app.domain.usecase.resolvePositionalBarcode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class ScannerProductChoices(
    val businessId: BusinessId,
    val scannedBarcode: String,
    val products: List<Product>,
    val mergeGroup: BarcodeGroup?,
    val stockLabels: Map<ProductId, String> = emptyMap(),
)

internal sealed interface CatalogScannerResult {
    data class Found(val product: Product, val resolvedAmbiguity: Boolean = false) : CatalogScannerResult
    data class Choices(val choices: ScannerProductChoices) : CatalogScannerResult
    data object ProbableMisread : CatalogScannerResult
    data object NotFound : CatalogScannerResult
}

/** Tras el exacto indexado, Inventario y el alta comparten el mismo selector del lector. */
internal suspend fun ProductRepository.resolveUnmatchedCatalogBarcode(
    businessId: BusinessId,
    barcode: String,
    salesRegistration: Boolean = false,
): CatalogScannerResult {
    if (barcode.any { it !in '0'..'9' }) return CatalogScannerResult.NotFound
    val catalog = listScannerIdentityCandidates(businessId)
    if (salesRegistration && catalog.any { it.sku == barcode }) return CatalogScannerResult.NotFound
    return when (val match = resolvePositionalBarcode(barcode, businessId, catalog)) {
        is PositionalBarcodeResult.Found -> CatalogScannerResult.Found(match.product)
        PositionalBarcodeResult.ProbableMisread -> CatalogScannerResult.ProbableMisread
        PositionalBarcodeResult.NotFound -> {
            val incomplete = salesRegistration && canonicalGtinOrNull(barcode) == null && catalog.any { product ->
                val stored = product.barcode
                stored != null && stored.length > barcode.length && BarcodeSimilarity.missingDigits(barcode, stored) != null
            }
            if (incomplete) CatalogScannerResult.ProbableMisread else CatalogScannerResult.NotFound
        }
        is PositionalBarcodeResult.Ambiguous -> {
            val options = BarcodeFamilies.orderOptions(match.products)
            val ids = options.mapTo(mutableSetOf()) { it.productId }
            val group = scannerCatalogDuplicateGroup(businessId, ids, catalog, barcode)
            val activity = if (group == null) scannerSalesActivity(businessId, ids) else emptyMap()
            val preferred = BarcodeFamilies.choose(barcode, options, group, activity)
            if (preferred != null) {
                val excluded = group?.products?.mapTo(mutableSetOf()) { it.productId } ?: setOf(preferred.productId)
                if (!BarcodeFamilies.hasCompetitor(barcode, businessId, catalog, excluded)) {
                    currentCoroutineContext().ensureActive()
                    val merged = group != null && group.keeper == preferred.productId &&
                        tryScannerMerge { mergeScannerGroupIfSafe(businessId, group, preferred.productId) }
                    val product = if (merged) {
                        checkNotNull(findById(preferred.productId)?.takeIf { it.businessId == businessId })
                    } else preferred
                    return CatalogScannerResult.Found(product, resolvedAmbiguity = true)
                }
            }
            val selectableGroup = group?.takeIf { scannerGroupAllowsChoice(businessId, it) }
            CatalogScannerResult.Choices(
                ScannerProductChoices(
                    businessId, barcode,
                    selectableGroup?.let { BarcodeFamilies.orderOptions(it.products) } ?: options,
                    selectableGroup,
                ),
            )
        }
    }
}

/** Revalida la selección que vio el trabajador antes de cambiar el stock o abrir la ficha. */
internal suspend fun ProductRepository.acceptScannerChoice(choices: ScannerProductChoices, chosen: ProductId): Product? {
    val expected = choices.products.firstOrNull { it.productId == chosen } ?: return null
    val current = findById(chosen)?.takeIf {
        it.businessId == choices.businessId && it.status == CatalogStatus.ACTIVE &&
            it.barcode == expected.barcode && it.version == expected.version
    } ?: return null
    val group = choices.mergeGroup ?: return current
    currentCoroutineContext().ensureActive()
    val merged = tryScannerMerge { mergeScannerGroupByChoice(choices.businessId, group, chosen) }
    return findById(chosen)?.takeIf {
        it.businessId == choices.businessId && it.status == CatalogStatus.ACTIVE &&
            (merged || it.barcode == current.barcode)
    }
}

// La unión es opcional. El 1.0.12 conserva la ficha elegida cuando no puede mover las existencias.
private suspend inline fun tryScannerMerge(operation: () -> Boolean): Boolean {
    return try {
        operation()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}
