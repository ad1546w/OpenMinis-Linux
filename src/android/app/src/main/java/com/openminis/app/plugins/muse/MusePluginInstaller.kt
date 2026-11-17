package com.openminis.app.plugins.muse

import android.content.Context
import android.util.Log
import com.openminis.app.data.UpdateVersionLogic
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.data.repository.SkillRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Phase-1 Muse plugin installer: downloads the package zip, reads its
 * plugin.json manifest, and installs ONLY the contributions Minis can run
 * natively today:
 *  - `skills` → files under filesDir/minis-global/skills/<id>/ (Muse skill
 *    files follow the same SKILL.md convention Minis already uses)
 *  - `mcpServers` (defensive, no catalog plugin ships one yet) → MCPRepository
 *
 * Everything else (tools / hooks / workspaceTools / configPage / agents /
 * providers …) requires the Muse JS runtime and is intentionally NOT
 * executed here — the manifest is parsed, classified and reported so the UI
 * can say exactly what was enabled and what needs the Muse app. No extracted
 * file is ever interpreted as code in this installer.
 */
object MusePluginInstaller {

    private const val TAG = "MusePluginInstaller"
    private const val PREFS = "muse_store"
    private const val KEY_REGISTRY = "installed_registry"

    /** Extraction guards, mirroring the spirit of the store's audit script. */
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    private const val MAX_ENTRIES = 800

    data class InstallOutcome(
        val skillIds: List<String>,
        val mcpIds: List<String>,
        val runtimeOnlyKeys: List<String>,
        val manifestVersion: String?,
    )

    sealed class InstallResult {
        data class Success(val outcome: InstallOutcome) : InstallResult()
        data class Failure(val reason: String) : InstallResult()
    }

    // ---- installed registry -------------------------------------------------

    fun installedRecord(context: Context, pluginId: String): JSONObject? =
        registry(context).optJSONObject(pluginId)

    fun isInstalled(context: Context, pluginId: String): Boolean =
        installedRecord(context, pluginId) != null

    fun installedVersion(context: Context, pluginId: String): String? =
        installedRecord(context, pluginId)?.optString("version")?.ifEmpty { null }

    /** True when the catalog ships a newer version than the installed record. */
    fun updateAvailable(context: Context, entry: MuseIndexEntry): Boolean {
        val installed = installedVersion(context, entry.id) ?: return false
        val latest = entry.version ?: return false
        return runCatching { UpdateVersionLogic.compareVersions(installed, latest) < 0 }
            .getOrDefault(false)
    }

    fun uninstall(context: Context, entry: MuseIndexEntry, skillRepository: SkillRepository?, mcpRepository: MCPRepository?) {
        val rec = installedRecord(context, entry.id) ?: return
        val skills = rec.optJSONArray("skills") ?: JSONArray()
        for (i in 0 until skills.length()) {
            val id = skills.optString(i)
            if (id.isEmpty()) continue
            runCatching { skillRepository?.delete(id) }
                .onFailure { Log.w(TAG, "skill delete failed for $id: ${it.message}") }
            // Even without a repository handle, drop the files so the next
            // reloadFromDisk() converges.
            File(File(context.filesDir, "minis-global/skills"), id).deleteRecursively()
        }
        val mcps = rec.optJSONArray("mcp") ?: JSONArray()
        for (i in 0 until mcps.length()) {
            val id = mcps.optString(i)
            if (id.isNotEmpty()) runCatching { mcpRepository?.delete(id) }
        }
        val registry = registry(context)
        registry.remove(entry.id)
        // org.json remove() returns the removed VALUE, not the map — mutate in place.
        prefs(context).edit().putString(
            KEY_REGISTRY,
            if (registry.length() == 0) null else registry.toString(),
        ).apply()
    }

    // ---- install ------------------------------------------------------------

    suspend fun install(
        context: Context,
        entry: MuseIndexEntry,
        skillRepository: SkillRepository?,
        mcpRepository: MCPRepository?,
    ): InstallResult = withContext(Dispatchers.IO) {
        val packageUrl = entry.packageUrl
        if (packageUrl.isNullOrBlank()) {
            return@withContext InstallResult.Failure("package_url missing in index entry")
        }

        // 1. download
        val bytes = MuseIndexClient.fetchBytes(context, packageUrl)
            ?: return@withContext InstallResult.Failure("download failed: $packageUrl")

        // 2. extract into a staging dir with zip-slip + size guards
        val staging = File(context.cacheDir, "muse-install/${sanitize(entry.id)}-${sanitize(entry.version ?: "0")}")
        staging.deleteRecursively()
        staging.mkdirs()
        val manifestJson = runCatching { extractZip(bytes, staging) }
            .getOrElse { return@withContext InstallResult.Failure("bad package: ${it.message}") }
            ?: return@withContext InstallResult.Failure("plugin.json not found in package")

        // 3. parse manifest
        val manifest = MuseIndexParser.parseManifest(manifestJson)
            ?: return@withContext InstallResult.Failure("plugin.json unparseable")
        if (manifest.id != entry.id && entry.id.isNotBlank()) {
            Log.w(TAG, "manifest id ${manifest.id} != index id ${entry.id}; continuing with manifest id")
        }

        // 4. install skills
        val installedSkills = mutableListOf<String>()
        for (skill in manifest.skills) {
            val sourceDir = skillSourceDir(staging, skill.file) ?: continue
            val skillId = "muse-${sanitize(manifest.id)}-${sanitize(skill.id)}".take(80)
            val target = File(context.filesDir, "minis-global/skills/$skillId")
            target.deleteRecursively()
            if (!copyTree(sourceDir, target)) {
                Log.w(TAG, "skill copy failed: ${skill.id}")
                continue
            }
            runCatching { skillRepository?.rescanFromDisk(skillId) }
                .onFailure { Log.w(TAG, "rescan failed for $skillId: ${it.message}") }
            installedSkills.add(skillId)
        }

        // 5. install MCP contributions
        val installedMcp = mutableListOf<String>()
        for (server in manifest.mcpServers) {
            val configId = "muse-${sanitize(server.id)}".take(80)
            val config = MCPRepository.MCPServerConfig(
                id = configId,
                note = "${manifest.name.resolve()} · ${server.name}",
                enabled = true,
                url = server.url,
                command = server.command,
                args = server.args,
            )
            runCatching { mcpRepository?.add(config) }
                .onSuccess { added -> if (added == true) installedMcp.add(configId) }
                .onFailure { Log.w(TAG, "mcp add failed for ${server.id}: ${it.message}") }
        }

        // 6. record + cleanup staging
        val rec = JSONObject()
            .put("version", manifest.version ?: entry.version ?: "")
            .put("installedAt", System.currentTimeMillis())
            .put("skills", JSONArray(installedSkills))
            .put("mcp", JSONArray(installedMcp))
            .put("runtimeKeys", JSONArray(manifest.runtimeOnlyKeys()))
        prefs(context).edit().putString(
            KEY_REGISTRY,
            registry(context).put(manifest.id, rec).toString(),
        ).apply()
        staging.deleteRecursively()

        InstallResult.Success(
            InstallOutcome(
                skillIds = installedSkills,
                mcpIds = installedMcp,
                runtimeOnlyKeys = manifest.runtimeOnlyKeys(),
                manifestVersion = manifest.version,
            ),
        )
    }

    // ---- helpers ------------------------------------------------------------

    /**
     * Extract zip entries under [staging]. Returns the contents of the root
     * plugin.json, or null when the package has no manifest. Guards against
     * path traversal (zip-slip) and oversized packages.
     */
    private fun extractZip(bytes: ByteArray, staging: File): String? {
        var total = 0L
        var entries = 0
        var manifest: String? = null
        var manifestDepth = Int.MAX_VALUE
        ZipInputStream(bytes.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > MAX_ENTRIES) throw IllegalStateException("too many entries")
                val name = entry.name.replace('\\', '/')
                if (name.contains("..") || name.startsWith('/')) {
                    throw IllegalStateException("unsafe zip path: $name")
                }
                if (entry.isDirectory) continue
                val target = File(staging, name)
                if (!target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                    throw IllegalStateException("zip-slip blocked: $name")
                }
                target.parentFile?.mkdirs()
                val data = zip.readBytes()
                total += data.size
                if (total > MAX_TOTAL_BYTES) throw IllegalStateException("package too large")
                target.writeBytes(data)
                // plugin.json may sit at the zip root or under one wrapper folder;
                // prefer the shallowest candidate.
                if (name == "plugin.json" || name.endsWith("/plugin.json")) {
                    val depth = name.count { it == '/' }
                    if (depth < manifestDepth) {
                        manifestDepth = depth
                        manifest = data.toString(Charsets.UTF_8)
                    }
                }
            }
        }
        return manifest
    }

    /** Resolve the directory that contains the manifest-referenced SKILL.md. */
    private fun skillSourceDir(staging: File, manifestFile: String): File? {
        val clean = manifestFile.replace('\\', '/').trimStart('/')
        if (clean.contains("..")) return null
        val file = File(staging, clean)
        val dir = file.parentFile ?: return null
        return if (dir.isDirectory && File(dir, "SKILL.md").isFile) dir else null
    }

    private fun copyTree(source: File, target: File): Boolean {
        if (!source.exists()) return false
        if (source.isDirectory) {
            target.mkdirs()
            source.listFiles()?.forEach { child ->
                if (!copyTree(child, File(target, child.name))) return false
            }
            return true
        }
        return runCatching { source.copyTo(target, overwrite = true).isFile }.getOrDefault(false)
    }

    fun sanitize(raw: String): String =
        raw.lowercase().replace(Regex("[^a-z0-9._-]"), "-").trim('-').ifEmpty { "x" }

    private fun registry(context: Context): JSONObject =
        runCatching { JSONObject(prefs(context).getString(KEY_REGISTRY, "{}").orEmpty()) }
            .getOrDefault(JSONObject())

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
