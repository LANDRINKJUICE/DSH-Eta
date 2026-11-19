package com.deepseekharness.app.etatools.skill

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private const val DEFAULT_SOURCE = "user"
private const val DEFAULT_INSTALL_STATE = "installed"
private const val REGISTRY_VERSION = 1
private const val REGISTRY_DIR_NAME = "skill_registry"
private const val REGISTRY_FILE_NAME = "registry.json"
private const val JSON_VERSION = "version"
private const val JSON_ENTRIES = "entries"
private const val JSON_SKILL_ID = "skillId"
private const val JSON_ENABLED = "enabled"
private const val JSON_SOURCE = "source"
private const val JSON_INSTALL_STATE = "installState"

/** 注册表中的技能状态记录。 */
data class SkillRegistryEntry(
    val enabled: Boolean = true,
    val source: String = DEFAULT_SOURCE,
    val installState: String = DEFAULT_INSTALL_STATE,
)

/**
 * 技能注册表持久化：替代原 Room `skill_registry` 表。
 *
 * 仅保存 `skillId -> {enabled, source, installState}`，技能正文仍保留在文件树中。
 * 存储落在 App 私有 `filesDir/skill_registry/registry.json`，整表以「临时文件 + 原子替换」
 * 写回，避免写一半留下损坏的注册表。对外接口与原 Room DAO 的读写语义一一对应：
 * - [read]       ↔ 容错读取，出错返回空表
 * - [readStrict] ↔ 严格读取（按 skillId 升序），出错抛出
 * - [write]      ↔ replaceRegistry（整表替换）
 * - [set]        ↔ upsertRegistryEntry
 * - [remove]     ↔ deleteRegistryEntry
 *
 * 另提供 [load]/[list]/[upsert]/[replaceAll]/[delete] 等价命名，便于不沿用旧 DAO 语义的调用方。
 */
class SkillRegistryStore(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val registryDir = File(appContext.filesDir, REGISTRY_DIR_NAME)
    private val registryFile = File(registryDir, REGISTRY_FILE_NAME)
    private val lock = Any()

    fun read(): LinkedHashMap<String, SkillRegistryEntry> {
        return runCatching { readStrict() }.getOrElse { linkedMapOf() }
    }

    fun readStrict(): LinkedHashMap<String, SkillRegistryEntry> = synchronized(lock) {
        runBlocking(Dispatchers.IO) { loadUnlocked() }
    }

    fun write(entries: Map<String, SkillRegistryEntry>) {
        synchronized(lock) {
            runBlocking(Dispatchers.IO) { saveUnlocked(entries) }
        }
    }

    fun set(skillId: String, entry: SkillRegistryEntry) {
        synchronized(lock) {
            runBlocking(Dispatchers.IO) {
                val current = loadUnlocked()
                current[skillId] = entry
                saveUnlocked(current)
            }
        }
    }

    fun remove(skillId: String) {
        synchronized(lock) {
            runBlocking(Dispatchers.IO) {
                val current = loadUnlocked()
                if (current.remove(skillId) != null) saveUnlocked(current)
            }
        }
    }

    fun load(): LinkedHashMap<String, SkillRegistryEntry> = read()

    fun list(): List<SkillRegistryEntry> = read().values.toList()

    fun upsert(skillId: String, entry: SkillRegistryEntry) = set(skillId, entry)

    fun replaceAll(entries: Map<String, SkillRegistryEntry>) = write(entries)

    fun delete(skillId: String) = remove(skillId)

    private fun loadUnlocked(): LinkedHashMap<String, SkillRegistryEntry> {
        if (!registryFile.isFile) return linkedMapOf()
        val root = JSONObject(
            String(Files.readAllBytes(registryFile.toPath()), StandardCharsets.UTF_8),
        )
        val arr = root.optJSONArray(JSON_ENTRIES) ?: return linkedMapOf()
        return (0 until arr.length())
            .mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val skillId = obj.optString(JSON_SKILL_ID)
                if (skillId.isBlank()) return@mapNotNull null
                skillId to SkillRegistryEntry(
                    enabled = obj.optBoolean(JSON_ENABLED, true),
                    source = obj.optString(JSON_SOURCE).ifBlank { DEFAULT_SOURCE },
                    installState = obj.optString(JSON_INSTALL_STATE).ifBlank { DEFAULT_INSTALL_STATE },
                )
            }
            .sortedBy { it.first }
            .associateTo(linkedMapOf()) { it }
    }

    private fun saveUnlocked(entries: Map<String, SkillRegistryEntry>) {
        if (!registryDir.isDirectory && !registryDir.mkdirs()) {
            throw IOException("Cannot create skill registry directory: $registryDir")
        }
        val root = JSONObject().put(JSON_VERSION, REGISTRY_VERSION)
        val arr = JSONArray()
        entries.toSortedMap().forEach { (skillId, value) ->
            arr.put(
                JSONObject()
                    .put(JSON_SKILL_ID, skillId)
                    .put(JSON_ENABLED, value.enabled)
                    .put(JSON_SOURCE, value.source)
                    .put(JSON_INSTALL_STATE, value.installState),
            )
        }
        root.put(JSON_ENTRIES, arr)
        val tmp = File(registryDir, "$REGISTRY_FILE_NAME.tmp")
        Files.write(tmp.toPath(), root.toString().toByteArray(StandardCharsets.UTF_8))
        try {
            try {
                Files.move(
                    tmp.toPath(),
                    registryFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), registryFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            runCatching { Files.deleteIfExists(tmp.toPath()) }
        }
    }
}
