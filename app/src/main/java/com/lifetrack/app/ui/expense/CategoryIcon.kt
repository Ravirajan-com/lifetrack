package com.lifetrack.app.ui.expense

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

@Composable
fun getCategoryIcon(name: String, emoji: String?): Any {
    if (!emoji.isNullOrBlank()) return emoji
    
    return when (name.lowercase()) {
        "food" -> Icons.Default.Restaurant
        "groceries" -> Icons.Default.ShoppingCart
        "transport" -> Icons.Default.DirectionsCar
        "shopping" -> Icons.Default.LocalMall
        "bills" -> Icons.Default.ReceiptLong
        "entertainment" -> Icons.Default.ConfirmationNumber
        "health" -> Icons.Default.MedicalServices
        "income", "salary" -> Icons.Default.Payments
        else -> Icons.Default.Category
    }
}
