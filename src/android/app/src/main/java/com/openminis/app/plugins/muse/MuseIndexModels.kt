package com.openminis.app.plugins.muse

import org.json.JSONArray
import org.json.JSONObject

/**
 * Muse-Plugins-Store index format (schema_version 1) + plugin package
 * manifest (plugin.json, manifestVersion 1) parsers.
 *
 * Data-only: mirrors the Muse app's PluginIndexModels/PluginManifest shape so
 * the store below can browse the same catalog. Parsing uses org.json to match
 * the rest of the plugin package (PluginRegistry/OnlinePluginStore); unknown
 * keys are ignored, malformed entries are skipped, never thrown.
 */

/** String-or-language-map text field (name / description in index and manifest). */
data class MuseLocalizedText(val values: Map<String, String>) {

    fun resolve(tag: String = "zh-CN"): String {
        val lower = tag.lowercase()
        values[lower]?.let { return it }
        val lang = lower.substringBefore('-')
        values[lang]?.let { return it }
        values[DEFAULT_KEY]?.let { return it }
        values["en"]?.let { return it }
        return values.values.firstOrNull().orEmpty()
    }

    companion object {
        const val DEFAULT_KEY = "default"

        fun parse(raw: Any?): MuseLocalizedText = when (raw) {
            is String -> MuseLocalizedText(linkedMapOf(DEFAULT_KEY to raw.trim()))
            is JSONObject -> {
                val out = linkedMapOf<String, String>()
                raw.keys().forEachRemaining { key ->
                    val text = raw.optString(key).trim()
                    if (text.isNotEmpty()) out[normalizeTag(key)] = text
                }
                MuseLocalizedText(out)
            }
            else -> MuseLocalizedText(emptyMap())
        }

        fun normalizeTag(tag: String): String =
            tag.trim().lowercase().replace('_', '-')
    }
}

data class MuseIndexEntry(
    val id: String,
    val name: MuseLocalizedText,
    val description: MuseLocalizedText,
    val author: String,
    val repository: String,
    val version: String?,
    val packageUrl: String?,
    val logo: String?,
    val homepage: String?,
    val license: String?,
    val tags: List<String>,
    val minAppVersion: String?,
)

/** Keys inside manifest `contributes` that need the Muse JS runtime. */
val MUSE_RUNTIME_CONTRIB_KEYS = setOf(
    "tools", "hooks", "workspaceTools", "configPage", "agents", "providers",
    "tips", "backup", "overlay", "prompts", "pages", "settingsPage", "ui",
)

data class MuseSkillContribution(val id: String, val file: String)

data class MuseMcpContribution(
    val id: String,
    val name: String,
    val url: String?,
    val command: String?,
    val args: List<String>,
)

data class MuseManifest(
    val id: String,
    val version: String?,
    val publisher: String,
    val name: MuseLocalizedText,
    val description: MuseLocalizedText,
    val runtimeType: String?,
    val skills: List<MuseSkillContribution>,
    val mcpServers: List<MuseMcpContribution>,
    /** Every `contributes` key present in the manifest, for the runtime notice. */
    val contributedKeys: List<String>,
) {
    val hasInstallableContributions: Boolean
        get() = skills.isNotEmpty() || mcpServers.isNotEmpty()

    /** Contribute keys that cannot run inside Minis without the Muse runtime. */
    fun runtimeOnlyKeys(): List<String> = contributedKeys.filter { it in MUSE_RUNTIME_CONTRIB_KEYS }
}

object MuseIndexParser {

    fun parseIndex(text: String): List<MuseIndexEntry> {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("plugins") ?: return emptyList()
        val out = mutableListOf<MuseIndexEntry>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val id = p.optString("id").trim()
            if (id.isEmpty()) continue
            out.add(
                MuseIndexEntry(
                    id = id,
                    name = MuseLocalizedText.parse(p.opt("name")),
                    description = MuseLocalizedText.parse(p.opt("description")),
                    author = p.optString("author").trim(),
                    repository = p.optString("repository").trim(),
                    version = p.optString("version").trim().ifEmpty { null },
                    packageUrl = p.optString("package_url").trim().ifEmpty { null },
                    logo = p.optString("logo").trim().ifEmpty { null },
                    homepage = p.optString("homepage").trim().ifEmpty { null },
                    license = p.optString("license").trim().ifEmpty { null },
                    tags = p.optJSONArray("tags").toStringList(),
                    minAppVersion = p.optString("min_app_version").trim().ifEmpty { null },
                ),
            )
        }
        return out
    }

    fun parseManifest(text: String): MuseManifest? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val id = root.optString("id").trim()
        if (id.isEmpty()) return null
        val contributes = root.optJSONObject("contributes") ?: JSONObject()
        val contributedKeys = mutableListOf<String>()
        contributes.keys().forEachRemaining { if (!contributedKeys.contains(it)) contributedKeys.add(it) }
        return MuseManifest(
            id = id,
            version = root.optString("version").trim().ifEmpty { null },
            publisher = root.optString("publisher").trim(),
            name = MuseLocalizedText.parse(root.opt("name")),
            description = MuseLocalizedText.parse(root.opt("description")),
            runtimeType = root.optJSONObject("runtime")?.optString("type")?.trim()?.ifEmpty { null },
            skills = parseSkills(contributes.optJSONArray("skills")),
            mcpServers = parseMcpServers(contributes.opt("mcpServers") ?: contributes.opt("mcp_servers")),
            contributedKeys = contributedKeys,
        )
    }

    private fun parseSkills(arr: JSONArray?): List<MuseSkillContribution> {
        if (arr == null) return emptyList()
        val out = mutableListOf<MuseSkillContribution>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            val file = o.optString("file").trim()
            if (id.isEmpty() || file.isEmpty()) continue
            out.add(MuseSkillContribution(id, file))
        }
        return out
    }

    /**
     * Defensive: no plugin in the current catalog ships mcpServers yet, but
     * the install path accepts the obvious shapes ({url} HTTP or {command,args}
     * stdio) so a future MCP-only plugin installs with zero code changes.
     */
    private fun parseMcpServers(raw: Any?): List<MuseMcpContribution> {
        val arr = when (raw) {
            is JSONArray -> raw
            is JSONObject -> JSONArray().put(raw)
            else -> return emptyList()
        }
        val out = mutableListOf<MuseMcpContribution>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            if (id.isEmpty()) continue
            out.add(
                MuseMcpContribution(
                    id = id,
                    name = o.optString("name", id).trim().ifEmpty { id },
                    url = o.optString("url").trim().ifEmpty { null },
                    command = o.optString("command").trim().ifEmpty { null },
                    args = o.optJSONArray("args").toStringList(),
                ),
            )
        }
        return out
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until length()) {
            val v = optString(i).trim()
            if (v.isNotEmpty()) out.add(v)
        }
        return out
    }
}
