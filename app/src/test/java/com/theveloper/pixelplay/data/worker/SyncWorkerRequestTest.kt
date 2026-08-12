package com.theveloper.pixelplay.data.worker

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncWorkerRequestTest {

    @Test
    fun `opportunistic sync request is not forced`() {
        val request = SyncWorker.syncWork()

        assertFalse(request.workSpec.input.getBoolean(SyncWorker.INPUT_FORCE_REFRESH, true))
    }

    @Test
    fun `force refresh request is forced`() {
        val request = SyncWorker.forceRefreshWork()

        assertTrue(request.workSpec.input.getBoolean(SyncWorker.INPUT_FORCE_REFRESH, false))
    }

    @Test
    fun `periodic maintenance request is not forced`() {
        val request = SyncWorker.periodicMaintenanceWork()

        assertFalse(request.workSpec.input.getBoolean(SyncWorker.INPUT_FORCE_REFRESH, true))
    }
}
