package com.facturastock.app.feature.inventory

import com.facturastock.app.domain.model.InventoryDataAlert
import com.facturastock.app.domain.model.InventoryReadItem
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.text.Normalizer
import java.util.Collections
import java.util.Locale

/**
 * Snapshot de nombres: los cambios de stock, costo o diagnóstico no repiten la normalización.
 * Preparar otro índice nunca modifica éste; el consumidor publica el candidato sólo junto con
 * el inventario aceptado. Así una cancelación no deja términos de una emisión incompleta.
 */
internal class InventorySearchIndex private constructor(
    private val names: Map<ProductId, IndexedName>,
    val termsByProduct: Map<ProductId, List<String>>,
) {
    suspend fun prepare(
        items: List<InventoryReadItem>,
        normalizeName: (String) -> String = { it.inventorySearchKey() },
    ): InventorySearchIndex {
        val context = currentCoroutineContext()
        context.ensureActive()
        if (items.size == names.size) {
            val entries = names.entries.iterator()
            var compared = 0
            val sameNames = items.all { item ->
                if (compared++ % 64 == 0) context.ensureActive()
                val previous = entries.next()
                previous.key == item.productId && previous.value.businessId == item.businessId &&
                    previous.value.source == item.productName
            }
            if (sameNames) return this
        }
        val nextNames = LinkedHashMap<ProductId, IndexedName>(items.size)
        val nextTerms = LinkedHashMap<ProductId, List<String>>(items.size)
        items.forEachIndexed { index, item ->
            if (index % 64 == 0) context.ensureActive()
            val name =
                names[item.productId]?.takeIf {
                    it.businessId == item.businessId && it.source == item.productName
                } ?: IndexedName(item.businessId, item.productName, listOf(normalizeName(item.productName)))
            nextNames[item.productId] = name
            nextTerms[item.productId] = name.terms
        }
        context.ensureActive()
        // Reconstruir las claves conserva el orden actual y descarta productos eliminados o
        // pertenecientes al negocio anterior, sin retener el catálogo completo en cada entrada.
        return InventorySearchIndex(
            Collections.unmodifiableMap(nextNames),
            Collections.unmodifiableMap(nextTerms),
        )
    }

    private class IndexedName(
        val businessId: BusinessId,
        val source: String,
        val terms: List<String>,
    )

    companion object {
        val Empty = InventorySearchIndex(emptyMap(), emptyMap())
    }
}

internal class InventoryNameSearch private constructor(
    private val source: List<InventoryReadItem>,
    private val needle: String,
    val items: List<InventoryReadItem>,
) {
    companion object {
        suspend fun filter(
            source: List<InventoryReadItem>,
            query: String,
            termsByProduct: Map<ProductId, List<String>>,
            previous: InventoryNameSearch? = null,
        ): InventoryNameSearch {
            val needle = query.inventorySearchKey().takeIf { it.length >= 2 }.orEmpty()
            val sameSource = previous?.takeIf { it.source === source }
            if (sameSource != null && sameSource.needle == needle) return sameSource
            if (needle.isEmpty()) return InventoryNameSearch(source, needle, source.visibleInventory())
            val candidates = if (sameSource != null && sameSource.needle.isNotEmpty() &&
                needle.startsWith(sameSource.needle)
            ) sameSource.items else source
            val context = currentCoroutineContext()
            val matches = candidates.filterIndexed { index, item ->
                if (index % 64 == 0) context.ensureActive()
                InventoryDataAlert.ARCHIVED_PRODUCT !in item.allAlerts &&
                    termsByProduct[item.productId]?.any { it.contains(needle) } == true
            }
            return InventoryNameSearch(source, needle, matches)
        }
    }
}

internal fun List<InventoryReadItem>.visibleInventory(): List<InventoryReadItem> =
    if (all { InventoryDataAlert.ARCHIVED_PRODUCT !in it.allAlerts }) this
    else filter { InventoryDataAlert.ARCHIVED_PRODUCT !in it.allAlerts }

private val inventorySearchMarks = Regex("\\p{M}+")

internal fun String.inventorySearchKey(): String =
    Normalizer
        .normalize(trim(), Normalizer.Form.NFD)
        .replace(inventorySearchMarks, "")
        .lowercase(Locale.ROOT)
