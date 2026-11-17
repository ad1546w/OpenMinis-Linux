package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.plugins.muse.MuseIndexClient
import com.openminis.app.plugins.muse.MuseIndexEntry
import com.openminis.app.plugins.muse.MusePluginInstaller
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// Muse-Plugins-Store section
// [T-muse-store] Browse/search/detail/install UI for the Muse-Plugins-Store
// catalog. Split from PluginMarketScreen.kt to honor the 800-line
// architecture policy. The Phase-1 installer enables skill/MCP contributions
// only — no plugin code is ever executed inside Minis (see
// MusePluginInstaller).
// ---------------------------------------------------------------------------

private val MuseBadgeInstalledContainer = Color(0xFF34D399)
private val MuseBadgeUpdateContainer = Color(0xFF5B8CFF)
private val MuseBadgeThirdPartyContainer = Color(0xFFFBBF24)

/** Small pill badge, same visual language as the Muse store's pills. */
@Composable
private fun MuseBadge(text: String, container: Color) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = container.copy(alpha = 0.14f),
        modifier = Modifier.padding(start = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = container,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
internal fun MuseStoreSection(
    onOpenDetail: (MuseIndexEntry) -> Unit,
) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(MuseIndexClient.cached(context)) }
    var loading by remember { mutableStateOf(true) }
    var live by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun reload(force: Boolean) {
        scope.launch {
            if (force) {
                val result = withContext(Dispatchers.IO) { MuseIndexClient.fetch(context) }
                entries = result.entries
                live = result.live
            } else {
                entries = MuseIndexClient.cached(context)
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload(force = true) }

    val languageTag = remember { Locale.getDefault().toLanguageTag() }
    val thirdParty = remember { MuseIndexClient.overrideUrl(context) != null }

    val filtered = remember(entries, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) entries
        else entries.filter {
            it.name.resolve(languageTag).lowercase().contains(q) ||
                it.id.lowercase().contains(q) ||
                it.author.lowercase().contains(q) ||
                it.description.resolve(languageTag).lowercase().contains(q)
        }
    }

    SettingsSection(
        header = stringResource(R.string.muse_store_section_header),
        footer = stringResource(R.string.muse_store_section_footer),
    ) {
        Text(
            text = when {
                loading && entries.isEmpty() -> stringResource(R.string.muse_store_loading)
                entries.isEmpty() -> stringResource(R.string.muse_store_empty)
                else -> stringResource(
                    if (live) R.string.muse_store_live else R.string.muse_store_cached,
                    filtered.size,
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        if (entries.size >= 3) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.muse_store_search_placeholder)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
        }
        filtered.forEachIndexed { index, entry ->
            SettingsRow(
                title = entry.name.resolve(languageTag),
                subtitle = buildString {
                    append(entry.description.resolve(languageTag).take(140))
                    append(" · v").append(entry.version ?: "?")
                },
                icon = Icons.Filled.Inventory2,
                onClick = { onOpenDetail(entry) },
                showDivider = index < filtered.lastIndex,
            )
            if (MusePluginInstaller.isInstalled(context, entry.id) ||
                MusePluginInstaller.updateAvailable(context, entry) ||
                thirdParty
            ) {
                Row(modifier = Modifier.padding(start = 16.dp, bottom = 6.dp)) {
                    if (MusePluginInstaller.isInstalled(context, entry.id) &&
                        !MusePluginInstaller.updateAvailable(context, entry)
                    ) {
                        MuseBadge(stringResource(R.string.muse_store_badge_installed), MuseBadgeInstalledContainer)
                    }
                    if (MusePluginInstaller.updateAvailable(context, entry)) {
                        MuseBadge(stringResource(R.string.muse_store_badge_update), MuseBadgeUpdateContainer)
                    }
                    if (thirdParty) {
                        MuseBadge(stringResource(R.string.muse_store_badge_third_party), MuseBadgeThirdPartyContainer)
                    }
                }
            }
        }
        SettingsRow(
            title = stringResource(R.string.muse_store_refresh),
            icon = Icons.Filled.Refresh,
            onClick = { reload(force = true) },
            showDivider = false,
        )
    }
}

@Composable
internal fun MuseStoreDetail(
    entry: MuseIndexEntry,
    skillRepository: SkillRepository?,
    mcpRepository: MCPRepository?,
    onBackToList: () -> Unit,
) {
    val context = LocalContext.current
    val languageTag = remember { Locale.getDefault().toLanguageTag() }
    val scope = rememberCoroutineScope()
    var installing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var confirmUninstall by remember { mutableStateOf(false) }
    var museTick by remember { mutableIntStateOf(0) }
    museTick // recompute installed badges after install/uninstall

    val installed = MusePluginInstaller.isInstalled(context, entry.id)
    val installedVersion = MusePluginInstaller.installedVersion(context, entry.id)
    val update = MusePluginInstaller.updateAvailable(context, entry)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        SettingsRow(
            title = stringResource(R.string.muse_store_back),
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            onClick = onBackToList,
            showDivider = false,
        )

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(11.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Inventory2,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = entry.name.resolve(languageTag),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (MuseIndexClient.overrideUrl(context) != null) {
                            MuseBadge(stringResource(R.string.muse_store_badge_third_party), MuseBadgeThirdPartyContainer)
                        }
                    }
                    if (entry.author.isNotBlank()) {
                        Text(
                            text = entry.author,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val description = entry.description.resolve(languageTag)
                    if (description.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        SettingsSection(header = stringResource(R.string.muse_store_section_info)) {
            SettingsRow(
                title = stringResource(R.string.muse_store_detail_version),
                subtitle = "v" + (entry.version ?: stringResource(R.string.muse_store_version_latest)),
                showDivider = entry.tags.isNotEmpty(),
            )
            if (entry.tags.isNotEmpty()) {
                SettingsRow(
                    title = stringResource(R.string.muse_store_detail_tags),
                    subtitle = entry.tags.joinToString(" · "),
                    showDivider = true,
                )
            }
            SettingsRow(
                title = stringResource(R.string.muse_store_detail_index_source),
                subtitle = if (MuseIndexClient.overrideUrl(context) != null) {
                    stringResource(R.string.muse_store_source_custom)
                } else {
                    stringResource(R.string.muse_store_source_official)
                },
                showDivider = true,
            )
            if (entry.repository.isNotBlank()) {
                SettingsRow(
                    title = stringResource(R.string.muse_store_detail_repository),
                    subtitle = entry.repository,
                    icon = Icons.Filled.Link,
                    onClick = { openMuseUrl(context, entry.repository) },
                    showDivider = entry.license != null || entry.homepage != null,
                )
            }
            if (entry.license != null) {
                SettingsRow(
                    title = stringResource(R.string.muse_store_detail_license),
                    subtitle = entry.license ?: "",
                    showDivider = entry.homepage != null,
                )
            }
            if (entry.homepage != null) {
                SettingsRow(
                    title = stringResource(R.string.muse_store_detail_homepage),
                    subtitle = entry.homepage ?: "",
                    icon = Icons.Outlined.Language,
                    onClick = { entry.homepage?.let { openMuseUrl(context, it) } },
                    showDivider = false,
                )
            }
        }

        // Install / update / uninstall actions.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
        ) {
            Button(
                onClick = {
                    if (installing) return@Button
                    if (entry.packageUrl.isNullOrBlank()) {
                        message = context.getString(R.string.muse_store_no_package)
                        messageIsError = true
                        return@Button
                    }
                    installing = true
                    message = null
                    scope.launch {
                        val result = MusePluginInstaller.install(context, entry, skillRepository, mcpRepository)
                        installing = false
                        museTick++
                        when (result) {
                            is MusePluginInstaller.InstallResult.Success -> {
                                val outcome = result.outcome
                                message = context.getString(
                                    R.string.muse_store_install_done,
                                    outcome.skillIds.size,
                                    outcome.mcpIds.size,
                                )
                                if (outcome.runtimeOnlyKeys.isNotEmpty()) {
                                    message = (message ?: "") + "\n" + context.getString(
                                        R.string.muse_store_runtime_notice,
                                        outcome.runtimeOnlyKeys.joinToString(", "),
                                    )
                                }
                                messageIsError = false
                            }
                            is MusePluginInstaller.InstallResult.Failure -> {
                                message = context.getString(R.string.muse_store_install_failed, result.reason)
                                messageIsError = true
                            }
                        }
                    }
                },
                enabled = !installing && entry.packageUrl != null,
                modifier = Modifier.weight(1f),
            ) {
                if (installing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    when {
                        installing -> stringResource(R.string.muse_store_installing)
                        installed && update -> stringResource(R.string.muse_store_update)
                        installed -> stringResource(R.string.muse_store_reinstall)
                        else -> stringResource(R.string.muse_store_install)
                    },
                )
            }
            if (installed) {
                Spacer(modifier = Modifier.width(10.dp))
                TextButton(onClick = { confirmUninstall = true }) {
                    Text(stringResource(R.string.muse_store_uninstall))
                }
            }
        }
        if (installed && installedVersion != null) {
            Text(
                text = stringResource(R.string.muse_store_installed_state, installedVersion),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Text(
            text = stringResource(R.string.muse_store_phase_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        message?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = if (messageIsError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
    }

    if (confirmUninstall) {
        AlertDialog(
            onDismissRequest = { confirmUninstall = false },
            title = { Text(entry.name.resolve(languageTag)) },
            text = { Text(stringResource(R.string.muse_store_uninstall_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmUninstall = false
                        MusePluginInstaller.uninstall(context, entry, skillRepository, mcpRepository)
                        museTick++
                        message = null
                    },
                ) {
                    Text(stringResource(R.string.muse_store_uninstall))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmUninstall = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

private fun openMuseUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
