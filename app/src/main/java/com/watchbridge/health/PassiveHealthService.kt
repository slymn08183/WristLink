package com.watchbridge.health

import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.UserActivityInfo
import com.watchbridge.service.WatchBridgeService

/** Health Services delivers passive data batches here; see [HealthCollector]. */
class PassiveHealthService : PassiveListenerService() {

    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        HealthCollector.onDataPoints(this, dataPoints)
        WatchBridgeService.companionServer?.onHealthDataAdded()
    }

    override fun onUserActivityInfoReceived(info: UserActivityInfo) {
        HealthCollector.onUserActivity(this, info)
        WatchBridgeService.companionServer?.onHealthDataAdded()
    }
}
