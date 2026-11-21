package com.example.refereetimer

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * One shared timer + event record for the whole watch app.
 * Both save themselves to storage, so the match survives the app closing.
 */
class WearApp : Application(), ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()

    private val provider by lazy {
        ViewModelProvider(viewModelStore, ViewModelProvider.AndroidViewModelFactory.getInstance(this))
    }

    val timerViewModel: TimerViewModel by lazy { provider[TimerViewModel::class.java] }
    val eventViewModel: MatchEventViewModel by lazy { provider[MatchEventViewModel::class.java] }
    val locationTagger: LocationTagger by lazy { LocationTagger(this) }
}
