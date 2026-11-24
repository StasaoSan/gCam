package com.stasao.gcam

/** Camera capabilities after the Binder caller's package and signature are verified. */
internal object CaptureClientAccess {
    enum class Client { PANEL, BLIND, PROJECTION, ZONES, UNKNOWN }

    fun clientFor(packages: Collection<String>): Client = when {
        "com.stasao.gpanel" in packages -> Client.PANEL
        "com.stasao.gblind" in packages -> Client.BLIND
        "com.stasao.gzones" in packages -> Client.ZONES
        "com.g992.anhud" in packages -> Client.PROJECTION
        else -> Client.UNKNOWN
    }

    fun canManageProjection(client: Client): Boolean = client == Client.PROJECTION || client == Client.PANEL

    fun canReportBlindHealth(client: Client): Boolean = client == Client.BLIND || client == Client.PANEL

    fun canUseHudSlot(client: Client, internal: Boolean, slot: Int): Boolean {
        if (slot !in 0..13) return false
        if (internal) return true
        return when (client) {
            Client.PANEL -> slot in 0..5 || slot in 10..11 || slot == 13
            Client.BLIND -> slot in listOf(2, 3, 5, 10, 11, 13)
            Client.ZONES -> slot in 10..12
            Client.PROJECTION -> slot in 0..5
            Client.UNKNOWN -> false
        }
    }
}
