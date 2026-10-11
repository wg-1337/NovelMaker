package cn.novelmaker.wg1337.ui.ai

import cn.novelmaker.wg1337.AppLogger
import cn.novelmaker.wg1337.Platform
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 多 API 配置管理。
 *
 * 支持保存多套 API 配置（名称 / 地址 / 模型 / 密钥），会话使用其中的「当前启用」那套，
 * 便于在官方直连、中转站、不同模型之间切换。
 *
 * ## 存储
 *
 * - 元数据（名称、地址、模型、当前启用项）→ `PreferencesManager` 的 `api_profiles` JSON
 * - 每个配置的密钥 → 独立键 `api_key_{profileId}`，经 `SecureSecretStore` 加密
 *   （Android Keystore / Windows DPAPI）
 *
 * 密钥单独存放而不是塞进同一个 JSON：避免列表序列化时把明文/密文混在一起，
 * 也便于单独清理。
 *
 * ## 与旧版本的兼容
 *
 * 旧版本只有一套配置（`ai_base_url` / `ai_model` / `ai_api_key_encrypted`）。
 * 首次读取时若发现没有配置列表但存在旧键，会自动迁移成名为「默认」的配置，
 * 用户原有的地址、模型与密钥都不会丢。
 */
object ApiProfileManager {

    private const val TAG = "ApiProfileManager"

    private const val KEY_PROFILES = "api_profiles"
    private const val KEY_ACTIVE_ID = "api_active_profile_id"

    /** 旧版单配置键（迁移用） */
    private const val LEGACY_BASE_URL = "ai_base_url"
    private const val LEGACY_MODEL = "ai_model"
    private const val LEGACY_API_KEY = "ai_api_key_encrypted"
    private const val LEGACY_API_KEY_PLAIN = "ai_api_key"

    /** 单个 API 配置的密钥存储键 */
    private fun keyFor(profileId: String) = "api_key_$profileId"

    /**
     * 一套 API 配置。
     * @param apiKey 仅在内存中使用；持久化时走加密存储，不写进列表 JSON
     */
    data class ApiProfile(
        val id: String = UUID.randomUUID().toString(),
        val name: String = "新配置",
        val baseUrl: String = DeepSeekApi.DEFAULT_BASE_URL,
        val model: String = DeepSeekApi.MODEL_FLASH,
        val apiKey: String = ""
    ) {
        /** 界面副标题 */
        val summary: String get() = "$baseUrl · $model"
    }

    private fun prefs() = Platform.keyValueStore()

    // ───────────────────── 读取 ─────────────────────

    /** 全部配置（含各自的密钥）；首次调用会执行旧版迁移 */
    fun loadProfiles(): List<ApiProfile> {
        val store = prefs()
        val raw = store.getString(KEY_PROFILES, null)
        if (raw.isNullOrBlank()) {
            val migrated = migrateLegacy()
            if (migrated.isNotEmpty()) {
                // 注意：必须把迁移过来的密钥落盘到该配置的独立键，
                // 否则只在本次调用返回，下次读取密钥就丢了。
                migrated.forEach { writeApiKey(it.id, it.apiKey) }
                saveProfiles(migrated)
                store.putString(KEY_ACTIVE_ID, migrated.first().id)
            }
            return migrated
        }
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val id = o.optString("id", "").ifEmpty { UUID.randomUUID().toString() }
                ApiProfile(
                    id = id,
                    name = o.optString("name", "未命名配置"),
                    baseUrl = o.optString("baseUrl", DeepSeekApi.DEFAULT_BASE_URL),
                    model = DeepSeekApi.normalizeModel(o.optString("model", DeepSeekApi.MODEL_FLASH)),
                    apiKey = readApiKey(id)
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "解析 API 配置列表失败，回落到默认配置", e)
            emptyList()
        }
    }

    /** 当前启用的配置；没有配置时返回内置默认（空密钥） */
    fun getActiveOrDefault(): ApiProfile {
        val list = loadProfiles()
        if (list.isEmpty()) return ApiProfile(name = "默认", apiKey = "")
        val activeId = prefs().getString(KEY_ACTIVE_ID, null)
        return list.firstOrNull { it.id == activeId } ?: list.first()
    }

    fun getActiveId(): String? {
        val list = loadProfiles()
        if (list.isEmpty()) return null
        val activeId = prefs().getString(KEY_ACTIVE_ID, null)
        return if (list.any { it.id == activeId }) activeId else list.first().id
    }

    fun setActive(profileId: String) {
        prefs().putString(KEY_ACTIVE_ID, profileId)
    }

    // ───────────────────── 写入 ─────────────────────

    /** 新增或更新一套配置。密钥单独加密存储。 */
    fun upsert(profile: ApiProfile) {
        val list = loadProfiles().toMutableList()
        val idx = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) list[idx] = profile else list.add(profile)
        saveProfiles(list)
        writeApiKey(profile.id, profile.apiKey)
        // 第一套配置自动设为启用
        if (list.size == 1) setActive(profile.id)
    }

    /** 删除一套配置 */
    fun delete(profileId: String) {
        val list = loadProfiles().filterNot { it.id == profileId }
        saveProfiles(list)
        prefs().remove(keyFor(profileId))
        // 删除的是当前启用项 → 切到第一套（或清空）
        if (getActiveId() == null || list.none { it.id == prefs().getString(KEY_ACTIVE_ID, null) }) {
            if (list.isNotEmpty()) setActive(list.first().id)
        }
    }

    private fun saveProfiles(profiles: List<ApiProfile>) {
        val arr = JSONArray()
        profiles.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("baseUrl", p.baseUrl)
                put("model", p.model)
                // 密钥不入此 JSON，见 writeApiKey
            })
        }
        prefs().putString(KEY_PROFILES, arr.toString())
    }

    // ───────────────────── 密钥 ─────────────────────

    private fun readApiKey(profileId: String): String {
        val stored = prefs().getString(keyFor(profileId), null) ?: return ""
        return try {
            Platform.secureSecretStore().decrypt(stored) ?: ""
        } catch (e: Exception) {
            AppLogger.w(TAG, "解密 API Key 失败（配置 $profileId）: ${e.message}")
            ""
        }
    }

    private fun writeApiKey(profileId: String, plain: String) {
        val key = keyFor(profileId)
        if (plain.isBlank()) {
            prefs().remove(key)
            return
        }
        try {
            prefs().putString(key, Platform.secureSecretStore().encrypt(plain))
        } catch (e: Exception) {
            AppLogger.e(TAG, "加密 API Key 失败（配置 $profileId）", e)
        }
    }

    // ───────────────────── 旧版迁移 ─────────────────────

    /**
     * 把旧版单配置迁移成一套 API 配置。
     * 旧键在迁移后**保留**（不删除），万一回退版本仍可用。
     */
    private fun migrateLegacy(): List<ApiProfile> {
        val store = prefs()
        val legacyUrl = store.getString(LEGACY_BASE_URL, null)
        val legacyModel = store.getString(LEGACY_MODEL, null)
        val hasLegacyKey = store.getString(LEGACY_API_KEY, null) != null ||
            store.getString(LEGACY_API_KEY_PLAIN, null) != null

        // 完全没有任何旧配置 → 给一套内置默认
        if (legacyUrl == null && legacyModel == null && !hasLegacyKey) {
            return listOf(ApiProfile(name = "DeepSeek 官方", apiKey = ""))
        }

        val key = try {
            val encrypted = store.getString(LEGACY_API_KEY, null)
            when {
                encrypted != null -> Platform.secureSecretStore().decrypt(encrypted) ?: ""
                else -> store.getString(LEGACY_API_KEY_PLAIN, null) ?: ""
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "迁移旧 API Key 失败: ${e.message}")
            ""
        }

        AppLogger.d(TAG, "已把旧版单 API 配置迁移为多 API 配置")
        return listOf(
            ApiProfile(
                name = "默认",
                baseUrl = legacyUrl ?: DeepSeekApi.DEFAULT_BASE_URL,
                model = DeepSeekApi.normalizeModel(legacyModel),
                apiKey = key
            )
        )
    }
}
