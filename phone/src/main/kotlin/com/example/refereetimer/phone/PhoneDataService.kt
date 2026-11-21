package com.example.refereetimer.phone

import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.example.refereetimer.shared.DataLayerPaths
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService

/**
 * Receives the watch's clock and events and hands them to the shared match record,
 * even when the phone app's screen isn't open.
 */
class PhoneDataService : WearableListenerService() {

    private val main = Handler(Looper.getMainLooper())
    private val vm get() = (application as PhoneApp).matchViewModel

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        var clockJson: String? = null
        val received = mutableListOf<Pair<Uri, String>>()

        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val item = event.dataItem
            val path = item.uri.path ?: continue
            val map = DataMapItem.fromDataItem(item).dataMap
            when {
                path == DataLayerPaths.PATH_MATCH_CLOCK ->
                    clockJson = map.getString(DataLayerPaths.KEY_CLOCK_JSON) ?: clockJson
                path.startsWith(DataLayerPaths.PATH_MATCH_EVENT + "/") ->
                    map.getString(DataLayerPaths.KEY_EVENT_JSON)?.let { received.add(item.uri to it) }
            }
        }

        val clock = clockJson
        if (clock != null || received.isNotEmpty()) {
            main.post {
                clock?.let { vm.applyWatchClock(it) }
                received.forEach { vm.addEventFromJson(it.second) }  // saved to storage here
            }
        }

        // Events are now saved on the phone; remove them from the sync queue.
        if (received.isNotEmpty()) {
            val client = Wearable.getDataClient(this)
            received.forEach { client.deleteDataItems(it.first) }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        // Older watch builds sent events as messages; still accepted.
        if (messageEvent.path == DataLayerPaths.PATH_MATCH_EVENT) {
            val payload = String(messageEvent.data)
            main.post { vm.addEventFromJson(payload) }
        }
    }
}
