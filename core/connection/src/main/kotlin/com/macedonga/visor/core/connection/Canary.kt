package com.macedonga.visor.core.connection

import com.macedonga.visor.core.session.DataXConnection

/** The service the canary asks. */
const val SVC_PEER_BUILD_INFO: Int = 100

/** TX: the phone announces itself. */
const val MT_PEER_APP_INFO: Int = 1

/** RX: the glasses answer with their build info. This arriving is the canary answering. */
const val MT_PEER_DEVICE_INFO: Int = 3

/** Announce on svc100 so the glasses answer with their build info. */
suspend fun announcePeerAppInfo(connection: DataXConnection) {
    val channel = connection.openChannel(SVC_PEER_BUILD_INFO)
    connection.send(
        channel,
        ByteArray(0),
        messageType = MT_PEER_APP_INFO,
        serviceId = SVC_PEER_BUILD_INFO,
    )
}
