package com.example

import android.app.Application
import com.example.data.AppDatabase
import com.example.data.ProductRepository

class ProductApplication : Application() {
    companion object {
        lateinit var instance: ProductApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    val database by lazy { AppDatabase.getDatabase(this) }
    val repository by lazy { ProductRepository(database.productDao()) }
}
