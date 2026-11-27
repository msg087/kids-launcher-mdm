package com.kidslauncher.mdm.ui.discover

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.kidslauncher.mdm.Application
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.apps.AppFilter
import com.kidslauncher.mdm.apps.AppInfo
import com.kidslauncher.mdm.databinding.ActivityDiscoverAppsBinding
import com.kidslauncher.mdm.databinding.ItemDiscoverAppBinding
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.LockReason
import com.kidslauncher.mdm.server.canLaunchChildApp
import com.kidslauncher.mdm.server.createMdmApi
import com.kidslauncher.mdm.server.dto.DiscoverApp
import com.kidslauncher.mdm.server.dto.DiscoverRequest
import com.kidslauncher.mdm.server.performMdmSync
import com.kidslauncher.mdm.server.reevaluateLockReasonFromCache
import com.kidslauncher.mdm.ui.LockActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private const val SOURCE_CATALOG = "catalog"
private const val SOURCE_PLAY = "play"
private const val SOURCE_REQUESTS = "requests"

/**
 * Child-facing search for parent-curated catalog entries and Google Play metadata. The server owns
 * every install/approval state; this screen only sends a package-name request back to it. An
 * already-installed app is opened only through the same [com.kidslauncher.mdm.actions.AppAction]
 * path used by home and the drawer.
 */
class DiscoverAppsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDiscoverAppsBinding
    private var requestGeneration = 0
    private var displayedResults: List<DiscoverApp> = emptyList()
    private var displayedOffline = false
    private var actionInFlightPackage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiscoverAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.discoverSearch.setOnClickListener { search() }
        binding.discoverQuery.setOnEditorActionListener { _: TextView, actionId, event ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                event?.keyCode == KeyEvent.KEYCODE_ENTER
            ) {
                search()
                true
            } else false
        }
        binding.discoverQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Never leave results for an old query tappable. Clearing catalog search returns to
                // the server-browsable family catalog; clearing Play never sends an empty query.
                invalidateDisplayedResults()
                if (s.isNullOrBlank() && source() == SOURCE_CATALOG) search()
            }
        })
        binding.discoverSource.setOnCheckedChangeListener { _, _ ->
            invalidateDisplayedResults()
            val history = source() == SOURCE_REQUESTS
            binding.discoverQuery.isVisible = !history
            binding.discoverSearch.setText(if (history) R.string.find_apps_refresh else R.string.find_apps_search)
            search() // Catalog supports blank browsing; Play's blank case is handled locally.
        }

        (application as Application).apps.observe(this) {
            // A normal tracked-app install changes this LiveData. Re-render so its row becomes
            // Open without requiring a second server query.
            renderResults(displayedResults, displayedOffline)
        }
        search() // Entering the screen browses the family catalog immediately, including blank q.
    }

    override fun onResume() {
        super.onResume()
        if (redirectToLockIfNeeded()) return
        renderResults(displayedResults, displayedOffline)
    }

    private fun source(): String = when (binding.discoverSource.checkedRadioButtonId) {
        R.id.discover_requests -> SOURCE_REQUESTS
        R.id.discover_play -> SOURCE_PLAY
        else -> SOURCE_CATALOG
    }

    private fun currentQuery() = if (source() == SOURCE_REQUESTS) "" else binding.discoverQuery.text.toString().trim()

    private fun search() = search(currentQuery(), source())

    private fun search(query: String, requestedSource: String) {
        val generation = ++requestGeneration
        displayedResults = emptyList()
        displayedOffline = false
        binding.discoverResults.removeAllViews()
        showMessage(null)

        // Google Play does not accept a blank query. Catalog does: blank is the family catalogue.
        if (requestedSource == SOURCE_PLAY && query.isBlank()) {
            binding.discoverSearch.isEnabled = true
            showMessage(getString(R.string.find_apps_play_query_required))
            return
        }
        binding.discoverSearch.isEnabled = false

        lifecycleScope.launch {
            val results = try {
                api()?.discoverApps(query, requestedSource)?.let { response ->
                    if (response.isSuccessful) response.body() else null
                }
            } catch (_: Exception) {
                null
            }
            // The text/source may have changed while this request was in flight.
            if (generation != requestGeneration || query != currentQuery() || requestedSource != source()) return@launch
            binding.discoverSearch.isEnabled = true
            if (results == null) {
                if (requestedSource == SOURCE_CATALOG) {
                    val local = localCatalogResults(query)
                    if (local.isNotEmpty()) {
                        displayedResults = local
                        displayedOffline = true
                        renderResults(local, offline = true)
                        showMessage(getString(R.string.find_apps_offline_installed))
                        return@launch
                    }
                }
                showMessage(getString(R.string.find_apps_failed))
            } else if (results.isEmpty()) {
                showMessage(getString(if (requestedSource == SOURCE_REQUESTS) R.string.find_apps_requests_empty else R.string.find_apps_empty))
            } else {
                displayedResults = results
                displayedOffline = false
                renderResults(results, offline = false)
                if (requestedSource == SOURCE_REQUESTS) showMessage(getString(R.string.find_apps_requests_note))
            }
        }
    }

    private fun invalidateDisplayedResults() {
        requestGeneration++
        binding.discoverSearch.isEnabled = true
        displayedResults = emptyList()
        displayedOffline = false
        binding.discoverResults.removeAllViews()
        showMessage(null)
    }

    private fun renderResults(results: List<DiscoverApp>, offline: Boolean) {
        binding.discoverResults.removeAllViews()
        results.forEach { addResult(it, offline) }
    }

    private fun addResult(result: DiscoverApp, offline: Boolean) {
        val row = ItemDiscoverAppBinding.inflate(layoutInflater, binding.discoverResults, false)
        row.discoverAppTitle.text = result.title
        row.discoverAppDeveloper.text =
            if (offline) getString(R.string.find_apps_installed_offline) else result.developer

        if (source() == SOURCE_REQUESTS) {
            row.discoverAppAction.isVisible = false
            row.discoverRequestStatus.isVisible = true
            row.discoverRequestStatus.setText(when (result.action) {
                "asked" -> R.string.find_apps_waiting
                "approved" -> R.string.find_apps_approved
                else -> R.string.find_apps_declined
            })
            binding.discoverResults.addView(row.root)
            return
        }
        // A server denial always wins over the local installed state; never offer a bypassing Open.
        val localApp = if (!offline && result.action == "denied") null else locallyLaunchable(result.packageName)
        if (localApp != null) {
            row.discoverAppAction.setText(R.string.find_apps_open)
            row.discoverAppAction.isEnabled = actionInFlightPackage == null
            row.discoverAppAction.setOnClickListener {
                if (redirectToLockIfNeeded()) return@setOnClickListener
                // Recheck both drawer visibility and policy at the final tap. The latter includes
                // the current lock, allowlist, app-group, and OS-suspension state; only then use
                // the ordinary launcher action.
                val current = locallyLaunchable(result.packageName)
                if (current != null && canLaunchChildApp(this, result.packageName)) {
                    current.getAction().invoke(this)
                }
            }
        } else {
            val actionResource = when (result.action) {
                "install" -> R.string.find_apps_install
                "ask" -> R.string.find_apps_ask
                "asked" -> R.string.find_apps_asked
                "installing" -> R.string.find_apps_installing
                "denied" -> R.string.find_apps_denied
                else -> R.string.find_apps_denied
            }
            row.discoverAppAction.setText(actionResource)
            val mayRequest = !offline &&
                actionInFlightPackage == null &&
                (result.action == "install" || result.action == "ask")
            row.discoverAppAction.isEnabled = mayRequest
            if (mayRequest) row.discoverAppAction.setOnClickListener { request(result) }
        }
        binding.discoverResults.addView(row.root)
    }

    private fun request(result: DiscoverApp) {
        if (actionInFlightPackage != null || redirectToLockIfNeeded()) return
        // Freeze the exact search that produced this row. A late response must not repaint a
        // different query/source, nor can a double tap send two install/approval requests.
        val query = currentQuery()
        val selectedSource = source()
        actionInFlightPackage = result.packageName
        binding.discoverSearch.isEnabled = false
        renderResults(displayedResults, displayedOffline)

        lifecycleScope.launch {
            val response = try {
                api()?.requestDiscoveredApp(DiscoverRequest(result.packageName))
            } catch (_: Exception) {
                null
            }
            if (response?.isSuccessful == true && result.action == "install") {
                try {
                    withContext(Dispatchers.IO) { performMdmSync(applicationContext) }
                } catch (_: Exception) {
                    // The accepted catalog assignment remains on the server for the normal sync.
                }
            }
            actionInFlightPackage = null
            if (query == currentQuery() && selectedSource == source()) {
                if (response?.isSuccessful == true) search(query, selectedSource) else {
                    binding.discoverSearch.isEnabled = true
                    renderResults(displayedResults, displayedOffline)
                    showMessage(getString(R.string.find_apps_failed))
                }
            } else {
                // The child may have switched lists while the action was in flight.
                // Restore actions on the current rows without repainting the old query.
                renderResults(displayedResults, displayedOffline)
                if (source() == SOURCE_REQUESTS && response?.isSuccessful == true) search()
            }
        }
    }

    private fun redirectToLockIfNeeded(): Boolean {
        reevaluateLockReasonFromCache(this)
        if (LauncherPreferences.mdm().lockReason() == LockReason.NONE) return false
        LockActivity.start(this)
        finish()
        return true
    }

    private fun api() = LauncherPreferences.mdm().let { preferences ->
        preferences.serverUrl()?.takeIf { it.isNotBlank() }?.let { serverUrl ->
            preferences.deviceToken()?.takeIf { it.isNotBlank() }?.let { token ->
                createMdmApi(serverUrl, token)
            }
        }
    }

    private fun locallyLaunchable(packageName: String) =
        (application as Application).apps.value
            ?.let { AppFilter(this)(it) }
            ?.firstOrNull { (it.getRawInfo() as? AppInfo)?.packageName == packageName }

    private fun localCatalogResults(query: String): List<DiscoverApp> {
        val needle = query.lowercase(Locale.ROOT)
        return (application as Application).apps.value
            ?.let { AppFilter(this)(it) }
            .orEmpty()
            .filter { it.getCustomLabel(this).lowercase(Locale.ROOT).contains(needle) }
            .map {
                val raw = it.getRawInfo() as? AppInfo ?: return@map null
                DiscoverApp(raw.packageName, it.getCustomLabel(this), "", "installed")
            }
            .filterNotNull()
    }

    private fun showMessage(message: String?) {
        binding.discoverMessage.isVisible = message != null
        binding.discoverMessage.text = message
    }
}
