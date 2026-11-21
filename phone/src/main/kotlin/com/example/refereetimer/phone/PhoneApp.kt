package com.example.refereetimer.phone

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * One shared "match brain" for the whole phone app, which saves itself to storage.
 */
class PhoneApp : Application(), ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()

    val matchViewModel: PhoneMatchViewModel by lazy {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                PhoneMatchViewModel(MatchStateStore(this@PhoneApp)) as T
        }
        ViewModelProvider(this, factory)[PhoneMatchViewModel::class.java]
    }
}
