package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.plugins.LocalToolPluginStore
import com.openminis.app.plugins.OnlinePluginStore
import com.openminis.app.plugins.PluginRegistry
import com.openminis.app.plugins.muse.MuseIndexClient
import com.openminis.app.plugins.muse.MuseIndexEntry
import com.openminis.app.plugins.muse.MusePluginInstaller
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PluginMarketScreen(
    mcpRepository: MCPRepository?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var plugins by remember { mutableStateOf(PluginRegistry.cached(context)) }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("") }
    var keyTarget by remember { mutableStateOf<PluginRegistry.RemotePlugin?>(null) }
    var keyDraft by remember { mutableStateOf("") }
    // [T-local-tool-plugins] Local command-template tools (LocalToolPluginStore).
    var localTools by remember { mutableStateOf(LocalToolPluginStore.load(context)) }
    var editTool by remember { mutableStateOf<LocalToolPluginStore.LocalToolDef?>(null) }
    var editToolIsNew by remember { mutableStateOf(false) }
    val fallbackServers = remember {
        kotlinx.coroutines.flow.MutableStateFlow(emptyList<MCPRepository.MCPServerConfig>())
    }
    val servers by (mcpRepository?.servers ?: fallbackServers).collectAsStateWithLifecycle()

    // [T-muse-store] Muse-Plugins-Store browse/install section. The detail view
    // replaces the whole scroll content while an entry is selected.
    var museDetail by remember { mutableStateOf<MuseIndexEntry?>(null) }

    val skillRepository = (LocalContext.current.applicationContext as? MinisApp)?.skillRepository

    LaunchedEffect(Unit) {
        mcpRepository?.reloadFromDisk()
        val result = withContext(Dispatchers.IO) { PluginRegistry.fetch(context) }
        plugins = result.first
        loading = false
        status = if (result.second) {
            context.getString(R.string.plugin_market_live, result.first.size)
        } else {
            context.getString(R.string.plugin_market_cached, result.first.size)
        }
    }

    var installedTick by remember { mutableStateOf(0) }
    installedTick // read so toggles recompose

    SettingsScaffold(
        title = stringResource(R.string.plugin_market_title),
        onBack = null,
    ) {
        val detail = museDetail
        if (detail != null) {
            MuseStoreDetail(
                entry = detail,
                skillRepository = skillRepository,
                mcpRepository = mcpRepository,
                onBackToList = { museDetail = null },
            )
        } else {
            if (status.isNotBlank()) {
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                )
            }

            MuseStoreSection(
                skillRepository = skillRepository,
                mcpRepository = mcpRepository,
                onOpenDetail = { museDetail = it },
            )

            // [T-dev-suite] Declarative dev-environment bundles (taixu-style
            // recipes): apt + setup scripts, downloaded on demand — the payloads
            // no longer ship in the APK. Also consumes the pending
            // minis://devsuite/install/<bundleId> deep link the agent can emit.
            // [T-dev-suite-link-consent] The deep link only opens the bundle's
            // confirmation dialog now (see DevSuiteSection.pendingInstallBundleId):
            // required components pre-checked, optional heavy ones not. Assembling
            // straight away from a chat-emitted link meant a prompt-injected
            // `minis://devsuite/install/android-suite` silently downloaded several
            // gigabytes, including the components this very screen lets users
            // deselect.
            var pendingSuiteId by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                pendingSuiteId = com.openminis.app.deeplink.DeepLinkCoordinator.consumePendingDevSuiteInstall()
            }
            DevSuiteSection(
                pendingInstallBundleId = pendingSuiteId,
                onPendingInstallConsumed = { pendingSuiteId = null },
            )

            SettingsSection(
                header = stringResource(R.string.plugin_market_section_online),
                footer = stringResource(R.string.plugin_market_footer_online) + "\n" +
                    stringResource(R.string.plugin_market_config_trust_note),
            ) {
                if (plugins.isEmpty()) {
                    Text(
                        if (loading) stringResource(R.string.plugin_market_loading)
                        else stringResource(R.string.plugin_market_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                plugins.forEachIndexed { index, plugin ->
                    val on = OnlinePluginStore.isInstalled(context, plugin)
                    SettingsSwitchRow(
                        title = plugin.name,
                        subtitle = buildString {
                            append(plugin.description.take(120).ifBlank { plugin.category })
                            if (plugin.authType == "api_key") append(" · API key")
                            append(" · ").append(plugin.tools.size).append(" tools")
                        },
                        checked = on,
                        onCheckedChange = { checked ->
                            if (checked) {
                                com.openminis.app.plugins.ConnectorNetworkPolicy.configurationBlockedReason(plugin.baseUrl)?.let {
                                    status = it
                                    return@SettingsSwitchRow
                                }
                                if (plugin.authType == "api_key" &&
                                    OnlinePluginStore.apiKey(context, plugin).isNullOrBlank()
                                ) {
                                    keyDraft = ""
                                    keyTarget = plugin
                                    return@SettingsSwitchRow
                                }
                                OnlinePluginStore.setInstalled(context, plugin, true)
                            } else {
                                OnlinePluginStore.setInstalled(context, plugin, false)
                            }
                            installedTick++
                        },
                        showDivider = index < plugins.lastIndex,
                    )
                    // [T-plugin-market-private-row-removed] No per-connector toggle:
                    // private-network targets are allowed by default (cloud
                    // metadata hosts remain blocked by ConnectorNetworkPolicy), so
                    // a switch that starts on and rarely changes is noise — it
                    // rendered once per catalog entry.
                }
            }

            // [T-local-tool-plugins] Locally-authored command-template tools.
            // The store itself landed earlier; this is the management surface
            // that makes them reachable: definitions join the agent tool list
            // (ChatViewModel.agentTools), execution dispatches in
            // ChatViewModelExecuteToolExt.executeTool via the shell pipeline.
            val localDisabledSuffix = stringResource(R.string.plugin_market_local_disabled)
            SettingsSection(
                header = stringResource(R.string.plugin_market_section_local),
                footer = stringResource(R.string.plugin_market_local_footer),
            ) {
                if (localTools.isEmpty()) {
                    Text(
                        stringResource(R.string.plugin_market_local_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                localTools.forEachIndexed { index, tool ->
                    SettingsRow(
                        title = tool.name,
                        subtitle = buildString {
                            append(tool.description.ifBlank { tool.command.take(60) })
                            append(" · ").append(tool.params.size).append(" params")
                            if (!tool.enabled) append(" · ").append(localDisabledSuffix)
                        },
                        onClick = {
                            editTool = tool
                            editToolIsNew = false
                        },
                        showDivider = index < localTools.lastIndex,
                    )
                }
                SettingsRow(
                    title = stringResource(R.string.plugin_market_local_add),
                    subtitle = stringResource(R.string.plugin_market_local_add_hint),
                    onClick = {
                        editTool = LocalToolPluginStore.LocalToolDef(
                            id = "lt-" + java.util.UUID.randomUUID().toString().take(8),
                            name = "",
                            description = "",
                            params = emptyList(),
                            command = "",
                            enabled = true,
                            createdAtMs = System.currentTimeMillis(),
                        )
                        editToolIsNew = true
                    },
                    showDivider = false,
                )
            }
        }
    }

    val pending = keyTarget
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { keyTarget = null },
            title = { Text(pending.name) },
            text = {
                OutlinedTextField(
                    value = keyDraft,
                    onValueChange = { keyDraft = it },
                    label = { Text(stringResource(R.string.plugin_market_api_key)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(onClick = { keyTarget = null }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    TextButton(
                        onClick = {
                            if (keyDraft.isBlank()) return@TextButton
                            OnlinePluginStore.setApiKey(context, pending, keyDraft)
                            OnlinePluginStore.setInstalled(context, pending, true)
                            keyTarget = null
                            installedTick++
                        },
                    ) {
                        Text(stringResource(R.string.plugin_market_install))
                    }
                }
            },
        )
    }

    // [T-local-tool-plugins] Editor for a local command-template tool.
    val editing = editTool
    if (editing != null) {
        LocalToolEditorDialog(
            initial = editing,
            isNew = editToolIsNew,
            onDismiss = { editTool = null },
            onSave = { saved ->
                LocalToolPluginStore.upsert(context, saved)
                localTools = LocalToolPluginStore.load(context)
                editTool = null
            },
            onDelete = (if (editToolIsNew) null else {
                {
                    LocalToolPluginStore.remove(context, editing.id)
                    localTools = LocalToolPluginStore.load(context)
                    editTool = null
                }
            }),
        )
    }
}

// ---------------------------------------------------------------------------
// Muse-Plugins-Store section
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
private fun MuseStoreSection(
    skillRepository: SkillRepository?,
    mcpRepository: MCPRepository?,
    onOpenDetail: (MuseIndexEntry) -> Unit,
) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(MuseIndexClient.cached(context)) }
    var loading by remember { mutableStateOf(true) }
    var live by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var museTick by remember { mutableIntStateOf(0) }
    museTick // install/uninstall badges recompose with this read
    val scope = rememberCoroutineScope()

    fun reload(force: Boolean) {
        scope.launch {
            if (force) {
                val result = withContext(Dispatchers.IO) { MuseIndexClient.fetch(context) }
                entries = result.first
                live = result.second
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
            val installed = MusePluginInstaller.isInstalled(context, entry.id)
            val update = MusePluginInstaller.updateAvailable(context, entry)
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
            // Badges are rendered under the row title inside subtitle when
            // SettingsRow has no slot for them; show them via a compact Row.
            if (installed || update || thirdParty) {
                Row(modifier = Modifier.padding(start = 16.dp, bottom = 6.dp)) {
                    if (installed && !update) MuseBadge(stringResource(R.string.muse_store_badge_installed), MuseBadgeInstalledContainer)
                    if (update) MuseBadge(stringResource(R.string.muse_store_badge_update), MuseBadgeUpdateContainer)
                    if (thirdParty) MuseBadge(stringResource(R.string.muse_store_badge_third_party), MuseBadgeThirdPartyContainer)
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
private fun MuseStoreDetail(
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
    museTick

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
                    androidx.compose.material3.Icon(
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
                    onClick = { openMuseUrl(context, entry.homepage ?: return@SettingsRow) },
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
                                    message += "\n" + context.getString(
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

@Composable
private fun LocalToolEditorDialog(
    initial: LocalToolPluginStore.LocalToolDef,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (LocalToolPluginStore.LocalToolDef) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember(initial) { mutableStateOf(initial.name) }
    var description by remember(initial) { mutableStateOf(initial.description) }
    var command by remember(initial) { mutableStateOf(initial.command) }
    var paramsText by remember(initial) {
        mutableStateOf(
            initial.params.joinToString("\n") { p ->
                listOfNotNull(p.name, p.description.ifBlank { null }, p.defaultValue).joinToString("|")
            },
        )
    }
    var enabled by remember(initial) { mutableStateOf(initial.enabled) }
    val nameValid = name.trim().matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))
    val commandValid = command.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isNew) R.string.plugin_market_local_add else R.string.plugin_market_local_edit,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.plugin_market_local_name)) },
                    isError = !nameValid,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.plugin_market_local_desc)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text(stringResource(R.string.plugin_market_local_command)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = paramsText,
                    onValueChange = { paramsText = it },
                    label = { Text(stringResource(R.string.plugin_market_local_params)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.plugin_market_local_params_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                    Text(
                        stringResource(R.string.plugin_market_local_enabled),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text(
                            stringResource(R.string.plugin_market_local_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(
                    onClick = {
                        if (!nameValid || !commandValid) return@TextButton
                        val params = paramsText.lines().mapNotNull { raw ->
                            val parts = raw.split('|')
                            val pn = parts.getOrNull(0)?.trim().orEmpty()
                            if (!pn.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*"))) null
                            else LocalToolPluginStore.LocalParam(
                                name = pn,
                                description = parts.getOrNull(1)?.trim().orEmpty(),
                                required = parts.getOrNull(2).isNullOrBlank(),
                                defaultValue = parts.getOrNull(2)?.trim()?.ifBlank { null },
                            )
                        }
                        onSave(
                            initial.copy(
                                name = name.trim(),
                                description = description.trim(),
                                command = command.trim(),
                                params = params,
                                enabled = enabled,
                            ),
                        )
                    },
                    enabled = nameValid && commandValid,
                ) {
                    Text(stringResource(R.string.plugin_market_local_save))
                }
            }
        },
    )
}
