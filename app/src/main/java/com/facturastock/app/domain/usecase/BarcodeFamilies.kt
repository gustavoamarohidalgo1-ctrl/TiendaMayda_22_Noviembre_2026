package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.ProductSalesActivity
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId

data class BarcodeFamily(val main: Product, val twins: List<Product>) {
    val products: List<Product> get() = listOf(main) + twins
}

/** El primer miembro es el principal con GTIN, aunque la actividad favorezca otra ficha. */
data class BarcodeGroup(
    val products: List<Product>,
    val keeper: ProductId,
    val stockAtScan: Set<ScannerBalanceSnapshot>? = null,
    val scannedBarcode: String? = null,
)

data class ScannerBalanceSnapshot(
    val productId: String,
    val locationId: String,
    val quantity: String,
    val averageUnitCost: String,
    val currencyCode: String,
    val version: Long,
)

/** Familias de códigos del lector. La clave para descubrir variantes es un recorte del GTIN. */
internal object BarcodeFamilies {
    fun find(products: Collection<Product>): List<BarcodeFamily> {
        val byBarcode = HashMap<String, MutableList<Product>>()
        val complete = ArrayList<Product>()
        for (product in products) {
            val barcode = product.barcode ?: continue
            if (!isNumericBarcode(barcode)) continue
            byBarcode.getOrPut(barcode, ::ArrayList).add(product)
            if (isValidGtin(barcode)) complete.add(product)
        }

        // null representa una variante que podría pertenecer a más de una ficha principal.
        val parents = HashMap<ProductId, Product?>()
        for (main in complete) {
            val barcode = requireNotNull(main.barcode)
            for (variant in positionalVariants(barcode)) {
                for (twin in byBarcode[variant].orEmpty()) {
                    if (main.businessId == twin.businessId) {
                        parents[twin.productId] = if (parents.containsKey(twin.productId)) null else main
                    }
                }
            }
        }
        if (parents.isEmpty()) return emptyList()

        val children = HashMap<ProductId, MutableList<Product>>()
        for (twin in products) {
            // Conserva el orden del catálogo. Una ficha que también tiene un padre no encabeza otra familia.
            val parent = parents[twin.productId] ?: continue
            if (!parents.containsKey(parent.productId)) children.getOrPut(parent.productId, ::ArrayList).add(twin)
        }
        return complete.mapNotNull { main -> children[main.productId]?.let { BarcodeFamily(main, it) } }
    }

    private fun positionalVariants(barcode: String): Set<String> = buildSet {
        for (missing in 1..3) {
            if (barcode.length - missing < 6) break
            for (atEnd in 0..1) {
                val afterFirst = missing - atEnd
                if (afterFirst in 0..2) add(barcode[0] + barcode.substring(1 + afterFirst, barcode.length - atEnd))
            }
        }
    }

    fun preferred(family: BarcodeFamily, activity: Map<ProductId, ProductSalesActivity>): Product =
        family.products.asSequence().filter { it.status == CatalogStatus.ACTIVE }
            .maxWithOrNull(activityComparator(activity).thenBy { it.productId == family.main.productId })
            ?: family.main

    fun choose(
        scannedBarcode: String,
        candidates: List<Product>,
        group: BarcodeGroup?,
        activity: Map<ProductId, ProductSalesActivity>,
    ): Product? {
        val active = candidates.filter { it.status == CatalogStatus.ACTIVE && it.barcode != null }
        if (active.isEmpty()) return null
        if (group != null) {
            val members = group.products.associateBy { it.productId }
            if (active.all { it.productId in members }) {
                return members[group.keeper]?.takeIf { it.status == CatalogStatus.ACTIVE }
                    ?: group.products.firstOrNull { it.status == CatalogStatus.ACTIVE }
            }
        }
        active.singleOrNull { product ->
            val barcode = requireNotNull(product.barcode)
            (barcode.length == 8 || barcode.length == 13) && isPositionalBarcode(scannedBarcode, barcode)
        }?.let { return it }
        if (!isValidGtin(scannedBarcode) || !active.all { isPositionalBarcode(requireNotNull(it.barcode), scannedBarcode) }) {
            return null
        }
        return active.maxWithOrNull(activityComparator(activity).thenBy { it.barcode!!.length }.thenBy { it.productId.value })
    }

    private fun activityComparator(activity: Map<ProductId, ProductSalesActivity>) =
        compareBy<Product> { activity[it.productId]?.recentLines ?: 0 }
            .thenBy { activity[it.productId]?.lastSoldAtMillis ?: Long.MIN_VALUE }

    fun hasCompetitor(
        scannedBarcode: String,
        businessId: BusinessId,
        products: Collection<Product>,
        excluded: Set<ProductId>,
    ): Boolean = products.any {
        it.businessId == businessId && it.productId !in excluded &&
            (isSingleSubstitutionOrAdjacentSwap(scannedBarcode, it.barcode) || isSingleSubstitutionOrAdjacentSwap(scannedBarcode, it.sku))
    }

    fun orderOptions(candidates: List<Product>): List<Product> =
        candidates.filter { it.status == CatalogStatus.ACTIVE && it.barcode != null }.sortedWith(optionOrder)

    private val optionOrder =
        compareByDescending<Product> { isValidGtin(requireNotNull(it.barcode)) }
            .thenByDescending { it.barcode!!.length }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            .thenBy { it.productId.value }
}
