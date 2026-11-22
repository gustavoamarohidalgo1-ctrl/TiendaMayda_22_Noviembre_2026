package com.facturastock.app.domain.model

/** Señales usadas al elegir entre fichas compatibles de un mismo producto. */
data class ProductSalesActivity(
    val recentLines: Int,
    val lastSoldAtMillis: Long?,
)
