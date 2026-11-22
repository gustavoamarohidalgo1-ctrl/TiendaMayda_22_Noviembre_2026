package com.facturastock.app.domain.usecase

import java.text.Normalizer
import java.util.Locale

internal object ScannerDuplicateNames {
    private val marks = Regex("\\p{M}+")
    private val words = Regex("[a-z0-9]+")
    private val genericWords = setOf(
        "cola", "kola", "litro", "litros", "personal", "grande", "chico", "chica", "pequeno",
        "pequena", "mediano", "mediana", "botella", "bolsa", "bolsaza", "sachet", "pack", "unidad",
        "unidades", "und", "gramos", "kilo", "kilos", "con", "sin", "del", "las", "los", "para",
    )

    fun areRelated(first: String, second: String): Boolean {
        val firstWords = significantWords(first).toHashSet()
        return firstWords.isNotEmpty() && significantWords(second).any { it in firstWords }
    }

    private fun significantWords(name: String): Sequence<String> {
        val normalized = Normalizer.normalize(name.trim(), Normalizer.Form.NFD).replace(marks, "").lowercase(Locale.ROOT)
        return words.findAll(normalized).map { it.value }
            .filter { it.length >= 3 && it !in genericWords && it.none(Char::isDigit) }
    }
}
