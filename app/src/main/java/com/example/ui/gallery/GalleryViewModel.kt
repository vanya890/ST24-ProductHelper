package com.example.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ProductEntity
import com.example.data.ProductRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GalleryViewModel(private val repository: ProductRepository) : ViewModel() {
    val products: StateFlow<List<ProductEntity>> = repository.allProducts
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun deleteProduct(product: ProductEntity) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // Also remove file if exists
                val file = java.io.File(product.imagePath)
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            repository.delete(product)
        }
    }
}
