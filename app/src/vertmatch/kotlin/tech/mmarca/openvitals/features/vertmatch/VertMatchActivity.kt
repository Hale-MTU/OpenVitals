package tech.mmarca.openvitals.features.vertmatch

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import tech.mmarca.openvitals.R
import tech.mmarca.openvitals.ui.components.DetailSectionCard
import tech.mmarca.openvitals.ui.components.OpenVitalsAdaptiveScaffold
import tech.mmarca.openvitals.ui.components.OpenVitalsButton
import tech.mmarca.openvitals.ui.components.OpenVitalsOutlinedButton
import tech.mmarca.openvitals.ui.theme.OpenVitalsTheme
import tech.mmarca.openvitals.ui.theme.Spacing
import tech.mmarca.openvitals.vertmatch.VertMatchSummary

/**
 * Connect, disconnect, and the training summary. Also the redirect target of the sign-in: the
 * browser comes back to `<applicationId>://vertmatch/callback`, which this activity (singleTask)
 * receives, in [onCreate] or [onNewIntent].
 */
@AndroidEntryPoint
internal class VertMatchActivity : ComponentActivity() {
    @Inject
    lateinit var connection: VertMatchConnection

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleRedirect(intent)
        setContent {
            OpenVitalsTheme {
                OpenVitalsAdaptiveScaffold(
                    title = stringResource(R.string.vertmatch_title),
                    navigationDestinations = emptyList(),
                    currentRoute = null,
                    showTopBar = true,
                    showNavigation = false,
                    canNavigateBack = true,
                    onNavigateBack = onBackPressedDispatcher::onBackPressed,
                    onNavigate = {},
                    navigationIcon = Icons.AutoMirrored.Outlined.ArrowBack,
                    navigationContentDescription = stringResource(R.string.cd_back),
                    action = null,
                ) { innerPadding ->
                    VertMatchScreen(
                        connection = connection,
                        onConnect = ::startSignIn,
                        contentPadding = innerPadding,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRedirect(intent)
    }

    private fun handleRedirect(intent: Intent?) {
        val uri = intent?.data
        if (!connection.isRedirect(uri)) return
        // Consumed once: a configuration change must not exchange the same code again.
        intent?.data = null
        lifecycleScope.launch { connection.completeSignIn(checkNotNull(uri)) }
    }

    private fun startSignIn() {
        lifecycleScope.launch {
            val signIn = connection.signInIntent()
            try {
                startActivity(signIn)
            } catch (_: ActivityNotFoundException) {
                // No browser on the phone: nothing to sign in with. The screen keeps saying disconnected.
            }
        }
    }
}

@Composable
private fun VertMatchScreen(connection: VertMatchConnection, onConnect: () -> Unit, contentPadding: PaddingValues) {
    val state by connection.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.status) {
        if (state.status == VertMatchStatus.Connected) connection.loadSummary()
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg)
            .widthIn(max = 720.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        DetailSectionCard(title = stringResource(R.string.vertmatch_what_title)) {
            Text(stringResource(R.string.vertmatch_what_body), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.vertmatch_privacy_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DetailSectionCard(title = stringResource(R.string.vertmatch_connection_title)) {
            Text(stringResource(statusText(state.status)), style = MaterialTheme.typography.bodyMedium)
            state.message?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
            when (state.status) {
                VertMatchStatus.Connected -> OpenVitalsOutlinedButton(
                    onClick = { scope.launch { connection.disconnect() } },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.vertmatch_disconnect)) }
                VertMatchStatus.Disconnected, VertMatchStatus.AppStopped -> OpenVitalsButton(
                    onClick = onConnect,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.vertmatch_connect)) }
                VertMatchStatus.NotConfigured -> Unit
            }
        }
        if (state.status == VertMatchStatus.Connected && connection.canReadSummary) {
            SummaryCard(
                summary = state.summary,
                busy = state.busy,
                onReload = { scope.launch { connection.loadSummary() } },
            )
        }
    }
}

@Composable
private fun SummaryCard(summary: VertMatchSummary?, busy: Boolean, onReload: () -> Unit) {
    DetailSectionCard(title = stringResource(R.string.vertmatch_summary_title)) {
        if (summary == null) {
            Text(stringResource(R.string.vertmatch_summary_none), style = MaterialTheme.typography.bodyMedium)
        } else {
            val race = summary.targetRace
            Text(
                text = if (race?.name != null) {
                    stringResource(R.string.vertmatch_summary_race, race.name.orEmpty(), race.date.orEmpty())
                } else {
                    stringResource(R.string.vertmatch_summary_no_race)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            val week = summary.week
            Text(
                text = if (week == null) {
                    stringResource(R.string.vertmatch_summary_week_unavailable)
                } else {
                    stringResource(
                        R.string.vertmatch_summary_week,
                        week.load.formatted(0),
                        week.ctl.formatted(1),
                        week.atl.formatted(1),
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        OpenVitalsOutlinedButton(onClick = onReload, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.vertmatch_summary_reload))
        }
    }
}

/** A dash, never a zero, for a value VertMatch left out: the document says null is "not available". */
private fun Double?.formatted(decimals: Int): String = this?.let { "%.${decimals}f".format(it) } ?: "-"
