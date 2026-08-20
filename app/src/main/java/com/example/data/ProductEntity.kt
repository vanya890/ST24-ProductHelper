package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val price: String,
    val link: String,
    val imagePath: String, // Final composed image path
    val timestamp: Long = System.currentTimeMillis()
)
