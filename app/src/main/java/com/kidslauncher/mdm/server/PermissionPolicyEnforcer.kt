package com.kidslauncher.mdm.server

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.UserManager
import android.util.Log
import androidx.preference.PreferenceManager
import com.kidslauncher.mdm.server.dto.PermissionPolicy
import com.kidslauncher.mdm.server.dto.PermissionResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

private const val LOG_TAG = "PermissionPolicy"
private const val MANAGED_GRANTS_KEY = "permission_policy_managed_grants_json"

/**
 * Device Owner runtime-permission enforcement.
 *
 * Broad `defaults` only visit [controllablePackages]: third-party apps and user-launchable system
 * apps. The launcher, HOME safety exemptions, and headless/core system packages are never touched.
 * Explicit app rules use the same safe target set; this intentionally refuses privileged/system
 * services rather than risking device stability. `defaultState` is Android's device-wide prompt/
 * auto-grant/auto-deny policy and therefore cannot be package-scoped.
 */
internal object PermissionPolicyEnforcer {
    private val validStates = setOf("default", "granted", "denied")
    private const val MAX_STATUS_RESULTS = 200
    /** Sync and LauncherApps callbacks can race in this process; never lose a ledger update. */
    private val ledgerLock = Any()

    fun apply(context: Context, policy: PermissionPolicy?): List<PermissionResult> {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            return listOf(PermissionResult("*", "*", "default", "skipped", "not device owner"))
        }
        val admin = ComponentName(context, MdmDeviceAdminReceiver::class.java)
        val results = mutableListOf<PermissionResult>()

        // A missing field means this server/device has not opted into permission management. It
        // must not clear a prior policy merely because the device is offline or a mixed-version
        // server omitted the field.
        if (policy == null) return results
        applyGlobalDefault(dpm, admin, policy.defaultState, results)
        // Hardware switches are intentionally based on the real cached/server policy, not the
        // override-effective app policy. A parent disabling camera/microphone remains in force
        // during offline override/pause; these are safety restrictions, not app access rules.
        applyHardwareRestrictions(context, dpm, admin, policy, results)

        applyRules(context, dpm, admin, policy, safeTargets(context), results)
        return boundResults(results)
    }

    /** Called for package install/update callbacks to converge before the next network sync. */
    fun enforcePackage(context: Context, packageName: String, policy: PermissionPolicy?): List<PermissionResult> {
        if (policy == null || packageName !in safeTargets(context)) return emptyList()
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            return listOf(PermissionResult(packageName, "*", "default", "skipped", "not device owner"))
        }
        val results = mutableListOf<PermissionResult>()
        // Deliberately no global policy/hardware write here: an install/update callback must only
        // converge this package's permission rules, not reapply unrelated DPM policy.
        applyRules(
            context, dpm, ComponentName(context, MdmDeviceAdminReceiver::class.java), policy,
            setOf(packageName), results, reportExcludedApps = false, reconcileAllPrevious = false,
        )
        return boundResults(results)
    }

    private fun applyRules(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        policy: PermissionPolicy,
        targets: Set<String>,
        results: MutableList<PermissionResult>,
        reportExcludedApps: Boolean = true,
        reconcileAllPrevious: Boolean = true,
    ) = synchronized(ledgerLock) {
        val desired = desiredRules(context, policy, targets, results, reportExcludedApps)
        val previous = loadManaged(context)
        val retainedForRetry = mutableSetOf<String>()
        val relevantPrevious = if (reconcileAllPrevious) previous else {
            previous.filter { splitKey(it)?.first in targets }.toSet()
        }
        (relevantPrevious - desired.keys).forEach { key ->
            val (packageName, permission) = splitKey(key) ?: return@forEach
            if (!restoreDefault(dpm, admin, context, packageName, permission, results, "stale policy entry")) {
                retainedForRetry += key
            }
        }
        desired.forEach { (key, state) ->
            val (packageName, permission) = splitKey(key) ?: return@forEach
            setGrantState(dpm, admin, context, packageName, permission, state, results)
        }
        saveManaged(context, (previous - relevantPrevious) + desired.keys + retainedForRetry)
    }

    private fun applyGlobalDefault(
        dpm: DevicePolicyManager, admin: ComponentName, requested: String, results: MutableList<PermissionResult>,
    ) {
        val state = requested.takeIf { it in validStates } ?: "default"
        val value = when (state) {
            "granted" -> DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT
            "denied" -> DevicePolicyManager.PERMISSION_POLICY_AUTO_DENY
            else -> DevicePolicyManager.PERMISSION_POLICY_PROMPT
        }
        try {
            if (dpm.getPermissionPolicy(admin) != value) dpm.setPermissionPolicy(admin, value)
            val actual = dpm.getPermissionPolicy(admin)
            results += PermissionResult("*", "*", state, if (actual == value) "applied" else "refused", "Device Owner permission policy=$actual")
        } catch (e: Exception) {
            results += PermissionResult("*", "*", state, "failed", exceptionDetail(e))
        }
    }

    private fun applyHardwareRestrictions(
        context: Context, dpm: DevicePolicyManager, admin: ComponentName, policy: PermissionPolicy?, results: MutableList<PermissionResult>,
    ) {
        val cameraDisabled = policy?.cameraDisabled ?: false
        try {
            if (dpm.getCameraDisabled(admin) != cameraDisabled) dpm.setCameraDisabled(admin, cameraDisabled)
            val actual = dpm.getCameraDisabled(admin)
            results += PermissionResult("*", Manifest.permission.CAMERA, if (cameraDisabled) "denied" else "default", if (actual == cameraDisabled) "applied" else "refused", "hardware camera disabled=$actual")
        } catch (e: Exception) {
            results += PermissionResult("*", Manifest.permission.CAMERA, if (cameraDisabled) "denied" else "default", "failed", exceptionDetail(e))
        }
        setRestriction(context, dpm, admin, UserManager.DISALLOW_UNMUTE_MICROPHONE, policy?.microphoneDisabled == true, results)
    }

    private fun setRestriction(
        context: Context, dpm: DevicePolicyManager, admin: ComponentName, restriction: String, enabled: Boolean, results: MutableList<PermissionResult>,
    ) = try {
        if (enabled) dpm.addUserRestriction(admin, restriction) else dpm.clearUserRestriction(admin, restriction)
        val actual = context.getSystemService(UserManager::class.java).hasUserRestriction(restriction)
        results += PermissionResult("*", Manifest.permission.RECORD_AUDIO, if (enabled) "denied" else "default", if (actual == enabled) "applied" else "refused", "hardware microphone restriction=$actual")
    } catch (e: Exception) {
        results += PermissionResult("*", Manifest.permission.RECORD_AUDIO, if (enabled) "denied" else "default", "failed", exceptionDetail(e))
    }

    private fun desiredRules(
        context: Context,
        policy: PermissionPolicy,
        targets: Set<String>,
        results: MutableList<PermissionResult>,
        reportExcludedApps: Boolean,
    ): Map<String, String> {
        val rules = mutableMapOf<String, String>()
        for (packageName in targets) {
            val requested = requestedPermissions(context, packageName)
            for ((permission, state) in policy.defaults) {
                if (permission in requested) addRule(rules, packageName, permission, state, results)
            }
        }
        for ((packageName, permissions) in policy.apps) {
            if (packageName !in targets) {
                if (reportExcludedApps) {
                    results += PermissionResult(packageName, "*", "default", "refused", "package is not a safe user-facing target")
                }
                continue
            }
            val requested = requestedPermissions(context, packageName)
            for ((permission, state) in permissions) {
                if (permission !in requested) {
                    results += PermissionResult(packageName, permission, state, "refused", "permission not requested by package")
                } else addRule(rules, packageName, permission, state, results)
            }
        }
        return rules
    }

    private fun addRule(rules: MutableMap<String, String>, packageName: String, permission: String, state: String, results: MutableList<PermissionResult>) {
        if (state !in validStates) {
            results += PermissionResult(packageName, permission, state, "refused", "state must be default, granted, or denied")
        } else {
            rules["$packageName\u0000$permission"] = state
        }
    }

    private fun setGrantState(
        dpm: DevicePolicyManager, admin: ComponentName, context: Context, packageName: String, permission: String, state: String,
        results: MutableList<PermissionResult>,
    ) {
        if (!isDangerous(context, permission)) {
            results += PermissionResult(packageName, permission, state, "refused", "not a dangerous runtime permission")
            return
        }
        val grant = when (state) {
            "granted" -> DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            "denied" -> DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
            else -> DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
        }
        try {
            val before = dpm.getPermissionGrantState(admin, packageName, permission)
            val accepted = if (before == grant) true else dpm.setPermissionGrantState(admin, packageName, permission, grant)
            val actual = dpm.getPermissionGrantState(admin, packageName, permission)
            // checkPermission(package, permission) queries the target package's UID. Calling
            // checkSelfPermission on a package Context still checks this DPC process's UID.
            val granted = context.packageManager.checkPermission(permission, packageName) == PackageManager.PERMISSION_GRANTED
            val permissionMatches = when (state) {
                "granted" -> granted
                "denied" -> !granted
                else -> true
            }
            val outcome = if (accepted && actual == grant && permissionMatches) "applied" else "refused"
            results += PermissionResult(packageName, permission, state, outcome, "grant_state=$actual granted=$granted")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed permission policy for $packageName $permission", e)
            results += PermissionResult(packageName, permission, state, "failed", exceptionDetail(e))
        }
    }

    private fun restoreDefault(dpm: DevicePolicyManager, admin: ComponentName, context: Context, packageName: String, permission: String, results: MutableList<PermissionResult>, detail: String): Boolean {
        try {
            context.packageManager.getPackageInfo(packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            // Server-driven/package-manager uninstall already removed the grant with its package.
            results += PermissionResult(packageName, permission, "default", "skipped", "package no longer installed")
            return true
        }
        if (!isDangerous(context, permission)) return true
        try {
            val ok = dpm.setPermissionGrantState(admin, packageName, permission, DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT)
            val actual = dpm.getPermissionGrantState(admin, packageName, permission)
            val restored = ok && actual == DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
            results += PermissionResult(packageName, permission, "default", if (restored) "restored" else "refused", "$detail grant_state=$actual")
            return restored
        } catch (_: PackageManager.NameNotFoundException) {
            // The package has gone away (for example, server-driven uninstall). No grant remains
            // for DPM to restore, so dropping its ledger key is correct and stops futile retries.
            results += PermissionResult(packageName, permission, "default", "skipped", "package no longer installed")
            return true
        } catch (e: Exception) {
            results += PermissionResult(packageName, permission, "default", "failed", exceptionDetail(e))
            return false
        }
    }

    private fun safeTargets(context: Context): Set<String> =
        controllablePackages(context).filter { it != context.packageName && it !in bootExemptPackages(context) }.toSet()

    private fun requestedPermissions(context: Context, packageName: String): Set<String> = try {
        context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            .requestedPermissions?.toSet().orEmpty()
    } catch (_: Exception) { emptySet() }

    private fun isDangerous(context: Context, permission: String): Boolean = try {
        context.packageManager.getPermissionInfo(permission, 0).protectionLevel and PermissionInfo.PROTECTION_MASK_BASE == PermissionInfo.PROTECTION_DANGEROUS
    } catch (_: Exception) { false }

    private fun loadManaged(context: Context): Set<String> = try {
        ServerJson.decodeFromString<List<String>>(PreferenceManager.getDefaultSharedPreferences(context).getString(MANAGED_GRANTS_KEY, null) ?: "[]").toSet()
    } catch (_: Exception) { emptySet() }

    private fun saveManaged(context: Context, keys: Set<String>) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(MANAGED_GRANTS_KEY, ServerJson.encodeToString(keys.toList())).apply()
    }

    private fun splitKey(key: String): Pair<String, String>? =
        key.indexOf('\u0000').takeIf { it > 0 }?.let { key.substring(0, it) to key.substring(it + 1) }

    private fun exceptionDetail(e: Exception) = e.message ?: e.javaClass.simpleName

    private fun boundResults(results: List<PermissionResult>): List<PermissionResult> {
        if (results.size <= MAX_STATUS_RESULTS) return results
        return results.take(MAX_STATUS_RESULTS - 1) +
            PermissionResult("*", "*", "default", "truncated", "${results.size - MAX_STATUS_RESULTS + 1} additional permission results omitted")
    }
}
