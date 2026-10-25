package app.kachai.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val Context.store: DataStore<Preferences> by preferencesDataStore("settings")

/** Все настройки и счётчики приложения в одном DataStore. */
class Prefs(context: Context) {
    private val store = context.applicationContext.store

    /** Тёмная — по умолчанию: так удобнее смотреть превью роликов, а на OLED-экранах экономится батарея. */
    val themeMode: Flow<ThemeMode> = store.data.map { p ->
        p[THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.DARK
    }
    /** Что открывать по умолчанию: видео или «только звук». */
    val audioByDefault: Flow<Boolean> = store.data.map { (it[AUDIO_DEFAULT] ?: 0) == 1 }
    /** Разрешение видео по умолчанию (короткая сторона кадра). */
    val videoHeight: Flow<Int> = store.data.map { it[VIDEO_HEIGHT] ?: 1080 }
    /** Формат для «только звук»: true — MP3, false — M4A. */
    val audioMp3: Flow<Boolean> = store.data.map { (it[AUDIO_MP3] ?: 0) == 1 }

    val data: Flow<Preferences> = store.data

    suspend fun setThemeMode(v: ThemeMode) = store.edit { it[THEME] = v.name }
    suspend fun setAudioByDefault(v: Boolean) = store.edit { it[AUDIO_DEFAULT] = if (v) 1 else 0 }
    suspend fun setVideoHeight(v: Int) = store.edit { it[VIDEO_HEIGHT] = v }
    suspend fun setAudioMp3(v: Boolean) = store.edit { it[AUDIO_MP3] = if (v) 1 else 0 }

    suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        store.edit { block(it) }
    }

    companion object {
        private val THEME = stringPreferencesKey("theme_mode")
        private val AUDIO_DEFAULT = intPreferencesKey("audio_by_default")
        private val VIDEO_HEIGHT = intPreferencesKey("video_height")
        private val AUDIO_MP3 = intPreferencesKey("audio_mp3")

        val PREMIUM_UNTIL = longPreferencesKey("premium_until")
        val PREMIUM_TOKEN = stringPreferencesKey("premium_token")
        val DEBUG_PREMIUM = intPreferencesKey("debug_premium")
        /** Плюс без автопродления, купленный в RuStore разово (на месяц или год). */
        val PASS_UNTIL = longPreferencesKey("pass_until")
        /** Плюс по промокоду и номера кодов, уже активированных на этом телефоне. */
        val PROMO_UNTIL = longPreferencesKey("promo_until")
        val PROMO_USED = stringSetPreferencesKey("promo_used")
        /** Сколько загрузок прошло успешно и просили ли уже оценку в RuStore. */
        val DOWNLOADS_DONE = intPreferencesKey("downloads_done")
        val REVIEW_ASKED = intPreferencesKey("review_asked")

        val QUOTA_DAY = stringPreferencesKey("quota_day")
        val QUOTA_USED = intPreferencesKey("quota_used")
        val QUOTA_BONUS = intPreferencesKey("quota_bonus")
        val QUOTA_REWARDS = intPreferencesKey("quota_rewards")

        val ENGINE_CHECKED_AT = longPreferencesKey("engine_checked_at")
        val DOWNLOADS_SINCE_AD = intPreferencesKey("downloads_since_ad")
        val DEVICE_ID = stringPreferencesKey("device_id")
    }
}
