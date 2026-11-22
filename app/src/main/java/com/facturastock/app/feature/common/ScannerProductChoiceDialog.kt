package com.facturastock.app.feature.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.facturastock.app.R
import com.facturastock.app.domain.model.id.ProductId

@Composable
internal fun ScannerProductChoiceDialog(
    choices: ScannerProductChoices,
    onSelect: (ProductId) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scanner_similar_products)) },
        text = {
            Column {
                Text(stringResource(if (choices.mergeGroup == null) R.string.scanner_similar_products_choose else R.string.scanner_similar_products_keep_one))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(choices.products, key = { it.productId.value }) { product ->
                        TextButton(onClick = { onSelect(product.productId) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(product.name)
                                Text(listOfNotNull(product.barcode, product.salePrice?.toString(), choices.stockLabels[product.productId])
                                    .joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
