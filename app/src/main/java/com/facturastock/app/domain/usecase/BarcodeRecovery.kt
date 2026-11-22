package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class BarcodeRecoveryDirection {
    SCANNED_CODE_INCOMPLETE,
    STORED_CODE_INCOMPLETE,
    GTIN_EQUIVALENT,
    POSITIONAL,
}

internal data class BarcodeRecoveryMatch(
    val productId: ProductId,
    val barcode: String,
    val missingDigits: Int,
    val direction: BarcodeRecoveryDirection,
)

/** Identifica la lectura sin modificar el catálogo. El llamador resuelve los exactos y el stock. */
internal suspend fun findAutomaticBarcodeRecovery(
    scannedBarcode: String,
    businessId: BusinessId,
    products: Collection<Product>,
): BarcodeRecoveryMatch? {
    val context = currentCoroutineContext()
    context.ensureActive()
    if (scannedBarcode.length !in 6..14 || !isNumericBarcode(scannedBarcode)) return null
    val candidate = findConservativeBarcodeCandidate(scannedBarcode, businessId, products)
    if (candidate != null) {
        return candidate.takeIf {
            it.direction == BarcodeRecoveryDirection.GTIN_EQUIVALENT ||
                isPositionalBarcode(scannedBarcode, it.barcode) || isPositionalBarcode(it.barcode, scannedBarcode)
        }
    }

    val result = resolvePositionalBarcode(scannedBarcode, businessId, products, rejectAnyExact = true)
    val product = (result as? PositionalBarcodeResult.Found)?.product ?: return null
    val stored = product.barcode ?: return null
    if (product.status != CatalogStatus.ACTIVE) return null
    products.forEachIndexed { index, other ->
        if (index % 64 == 0) context.ensureActive()
        if (other.businessId == businessId && other.productId != product.productId &&
            (isSingleSubstitutionOrAdjacentSwap(scannedBarcode, other.barcode) ||
                isSingleSubstitutionOrAdjacentSwap(scannedBarcode, other.sku))
        ) return null
    }
    context.ensureActive()
    return BarcodeRecoveryMatch(
        product.productId,
        stored,
        kotlin.math.abs(scannedBarcode.length - stored.length),
        BarcodeRecoveryDirection.POSITIONAL,
    )
}

/** Una subsecuencia amplia sirve de evidencia. Su aceptación se decide por el patrón físico. */
internal suspend fun findConservativeBarcodeCandidate(
    scannedBarcode: String,
    businessId: BusinessId,
    products: Collection<Product>,
): BarcodeRecoveryMatch? {
    val context = currentCoroutineContext()
    val scannedGtin = canonicalGtinOrNull(scannedBarcode)
    var candidate: BarcodeRecoveryMatch? = null
    var candidateIsActive = false
    var identifierCompetitorId: ProductId? = null
    products.forEachIndexed { index, product ->
        if (index % 64 == 0) context.ensureActive()
        if (product.businessId != businessId) return@forEachIndexed
        // Defensa adicional si un catálogo actualizado ya contiene el exacto consultado antes.
        if (product.barcode == scannedBarcode || product.sku == scannedBarcode) return null
        if (isSingleSubstitutionOrAdjacentSwap(scannedBarcode, product.barcode) ||
            isCompetingSku(scannedBarcode, scannedGtin, product.sku)
        ) {
            // Dos identidades distintas no pueden ser ambas el eventual ganador. Un SKU del
            // propio ganador, en cambio, no es evidencia de que pertenezca a otro producto.
            if (identifierCompetitorId != null && identifierCompetitorId != product.productId) return null
            identifierCompetitorId = product.productId
        }
        val stored = product.barcode ?: return@forEachIndexed
        // Una lectura incompleta no puede tener equivalencia GTIN. En ese caso basta validar
        // el checksum del único candidato al final, sin reconstruir cada GTIN del catálogo.
        val equivalent = scannedGtin != null && scannedGtin == canonicalGtinOrNull(stored)
        val missing =
            if (equivalent) {
                kotlin.math.abs(scannedBarcode.length - stored.length)
            } else {
                BarcodeSimilarity.missingDigits(scannedBarcode, stored) ?: return@forEachIndexed
            }
        val match =
            BarcodeRecoveryMatch(
                productId = product.productId,
                barcode = stored,
                missingDigits = missing,
                direction =
                    when {
                        equivalent -> BarcodeRecoveryDirection.GTIN_EQUIVALENT
                        scannedBarcode.length < stored.length -> BarcodeRecoveryDirection.SCANNED_CODE_INCOMPLETE
                        else -> BarcodeRecoveryDirection.STORED_CODE_INCOMPLETE
                    },
            )
        // Contar antes de validar estado, longitud o checksum evita fabricar un ganador único.
        if (candidate != null && candidate != match) return null
        candidateIsActive =
            (candidate == null || candidateIsActive) && product.status == CatalogStatus.ACTIVE
        candidate = match
    }
    context.ensureActive()
    val match = candidate ?: return null
    if (!candidateIsActive) return null
    if (identifierCompetitorId != null && identifierCompetitorId != match.productId) return null
    if (match.direction == BarcodeRecoveryDirection.GTIN_EQUIVALENT) return match
    if (match.missingDigits !in 1..2 || minOf(scannedBarcode.length, match.barcode.length) < 6) return null

    val storedGtin = canonicalGtinOrNull(match.barcode)
    if (scannedGtin != null && storedGtin != null && scannedGtin != storedGtin) return null
    val longerGtin = if (scannedBarcode.length > match.barcode.length) scannedGtin else storedGtin
    return match.takeIf { longerGtin != null }
}

/**
 * Señala un exacto posiblemente truncado; nunca lo reemplaza por el código largo.
 *
 * Un código personalizado sigue siendo válido para el catálogo. Sólo se solicita una elección
 * cuando no es un GTIN completo válido y otro producto tiene un GTIN válido compatible con una
 * o dos omisiones. Los exactos GTIN válidos conservan su identidad, aunque se parezcan a otro.
 * Se devuelven también archivados y agotados: la disponibilidad no elimina la ambigüedad.
 */
internal suspend fun findSuspiciousExactBarcodeMatches(
    scannedBarcode: String,
    businessId: BusinessId,
    exactProductId: ProductId,
    products: Collection<Product>,
): List<Product> {
    val context = currentCoroutineContext()
    context.ensureActive()
    if (!requiresSuspiciousExactBarcodeReview(scannedBarcode)) return emptyList()
    val matches = LinkedHashMap<ProductId, Product>()
    products.forEachIndexed { index, product ->
        if (index % 64 == 0) context.ensureActive()
        if (product.businessId != businessId || product.productId == exactProductId) return@forEachIndexed
        val stored = product.barcode ?: return@forEachIndexed
        if (stored.length - scannedBarcode.length !in 1..2 || canonicalGtinOrNull(stored) == null) {
            return@forEachIndexed
        }
        if (BarcodeSimilarity.missingDigits(scannedBarcode, stored) != null) {
            matches.putIfAbsent(product.productId, product)
        }
    }
    context.ensureActive()
    return matches.values.toList()
}

/** La misma condición evita cargar el catálogo cuando la política ya conserva el exacto. */
internal fun requiresSuspiciousExactBarcodeReview(scannedBarcode: String): Boolean =
    scannedBarcode.length in 6..13 && scannedBarcode.all { it in '0'..'9' } &&
        canonicalGtinOrNull(scannedBarcode) == null

/** Un SKU registrado puede explicar la lectura, aunque no autorice recuperar por SKU. */
private fun isCompetingSku(
    scanned: String,
    scannedGtin: String?,
    sku: String?,
): Boolean {
    if (sku == null) return false
    return isSingleSubstitutionOrAdjacentSwap(scanned, sku) ||
        BarcodeSimilarity.missingDigits(scanned, sku) != null ||
        (scannedGtin != null && scannedGtin == canonicalGtinOrNull(sku))
}

/** O(n), sin matrices ni cadenas intermedias; la lectura ya se validó como numérica. */
internal fun isSingleSubstitutionOrAdjacentSwap(
    scanned: String,
    stored: String?,
): Boolean {
    if (stored == null || stored.length != scanned.length) return false
    var firstDifference = -1
    var secondDifference = -1
    for (index in stored.indices) {
        if (stored[index] !in '0'..'9') return false
        if (stored[index] == scanned[index]) continue
        when {
            firstDifference < 0 -> firstDifference = index
            secondDifference < 0 -> secondDifference = index
            else -> return false
        }
    }
    if (firstDifference < 0) return false
    if (secondDifference < 0) return true
    return secondDifference == firstDifference + 1 &&
        stored[firstDifference] == scanned[secondDifference] &&
        stored[secondDifference] == scanned[firstDifference]
}

/** El padding se utiliza sólo para comparar identidades GTIN, nunca para reescribir el catálogo. */
internal fun canonicalGtinOrNull(value: String): String? =
    if (isValidGtin(value)) value.padStart(14, '0') else null

internal fun isValidGtin(value: String): Boolean {
    if (value.length != 8 && value.length != 12 && value.length != 13 && value.length != 14) return false
    var sum = 0
    var weight = 1
    for (index in value.lastIndex downTo 0) {
        val digit = value[index] - '0'
        if (digit !in 0..9) return false
        sum += digit * weight
        weight = 4 - weight
    }
    return sum % 10 == 0
}

internal fun isNumericBarcode(code: String): Boolean = code.isNotEmpty() && code.all { it in '0'..'9' }

/** El lector conserva el primer dígito y un tramo continuo del GTIN completo. */
internal fun isPositionalBarcode(short: String, full: String): Boolean {
    val missing = full.length - short.length
    if (missing !in 1..3 || short.length < 6 || short[0] != full[0] ||
        !isValidGtin(full) || !isNumericBarcode(short)
    ) return false
    for (atEnd in 0..1) {
        val afterFirst = missing - atEnd
        if (afterFirst in 0..2 && full.regionMatches(1 + afterFirst, short, 1, short.length - 1)) return true
    }
    return false
}

internal sealed interface PositionalBarcodeResult {
    data class Found(val product: Product) : PositionalBarcodeResult
    data class Ambiguous(val products: List<Product>) : PositionalBarcodeResult
    data object ProbableMisread : PositionalBarcodeResult
    data object NotFound : PositionalBarcodeResult
}

internal fun isProbableBarcodeMisread(code: String): Boolean =
    code.length in 11..13 && isNumericBarcode(code) && !isValidGtin(code)

internal suspend fun resolvePositionalBarcode(
    scannedBarcode: String,
    businessId: BusinessId,
    products: Collection<Product>,
    rejectAnyExact: Boolean = false,
): PositionalBarcodeResult {
    val context = currentCoroutineContext()
    context.ensureActive()
    if (!isNumericBarcode(scannedBarcode)) return PositionalBarcodeResult.NotFound
    val candidates = LinkedHashMap<ProductId, Product>()
    products.forEachIndexed { index, product ->
        if (index % 64 == 0) context.ensureActive()
        if (product.businessId != businessId) return@forEachIndexed
        if (product.sku == scannedBarcode) return PositionalBarcodeResult.NotFound
        val stored = product.barcode ?: return@forEachIndexed
        if (stored == scannedBarcode) {
            return if (rejectAnyExact) PositionalBarcodeResult.NotFound else PositionalBarcodeResult.Found(product)
        }
        if (isPositionalBarcode(scannedBarcode, stored) || isPositionalBarcode(stored, scannedBarcode)) {
            candidates.putIfAbsent(product.productId, product)
        }
    }
    context.ensureActive()
    val values = candidates.values
    val winner = values.singleOrNull() ?: values.singleOrNull { it.status == CatalogStatus.ACTIVE }
    return when {
        winner != null -> PositionalBarcodeResult.Found(winner)
        values.isNotEmpty() -> PositionalBarcodeResult.Ambiguous(values.toList())
        isProbableBarcodeMisread(scannedBarcode) -> PositionalBarcodeResult.ProbableMisread
        else -> PositionalBarcodeResult.NotFound
    }
}
