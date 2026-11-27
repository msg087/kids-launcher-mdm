package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/**
 * A child-visible result from the server's catalog or Google Play search. `action` is intentionally
 * server-owned: the client may only request `install`/`ask`; it never installs arbitrary APKs.
 */
@Serializable
data class DiscoverApp(
    val packageName: String,
    val title: String,
    val developer: String,
    val action: String,
)

@Serializable
data class DiscoverRequest(val packageName: String)
