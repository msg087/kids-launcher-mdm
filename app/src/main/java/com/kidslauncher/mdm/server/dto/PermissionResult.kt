package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/** One attempted permission-policy action, reported with the normal device status heartbeat. */
@Serializable
data class PermissionResult(
    val packageName: String,
    val permission: String,
    val state: String,
    val outcome: String,
    val detail: String,
)
