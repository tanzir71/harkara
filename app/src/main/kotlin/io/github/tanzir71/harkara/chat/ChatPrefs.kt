package io.github.tanzir71.harkara.chat

import io.github.tanzir71.harkara.settings.AppSettings

/** Small chat settings kept on the phone only: your display name and how far back DMs were fetched. */
interface ChatPrefs {
    /** The name sent with your messages; null means anonymous. Never a phone number. */
    var displayName: String?

    /** True once we asked for a name (asked once, when you send your first message). */
    var nameAsked: Boolean

    /** Unix seconds of the last finished DM fetch from relays (0: never). */
    var dmSyncedAt: Long
}

class InMemoryChatPrefs(
    override var displayName: String? = null,
    override var nameAsked: Boolean = false,
    override var dmSyncedAt: Long = 0,
) : ChatPrefs

/**
 * [ChatPrefs] in the app's SharedPreferences, so panic wipe clears them with everything else. The name is
 * the same one Tasks and Khata use.
 */
class SettingsChatPrefs(private val settings: AppSettings) : ChatPrefs {
    override var displayName: String?
        get() = settings.state.value.displayName
        set(value) {
            if (!value.isNullOrBlank()) settings.setDisplayName(value)
        }

    override var nameAsked: Boolean
        get() = settings.getString(KEY_NAME_ASKED) == "1"
        set(value) = settings.putString(KEY_NAME_ASKED, if (value) "1" else null)

    override var dmSyncedAt: Long
        get() = settings.getLong(KEY_DM_SYNC)
        set(value) = settings.putLong(KEY_DM_SYNC, value)

    private companion object {
        const val KEY_NAME_ASKED = "chat.name_asked"
        const val KEY_DM_SYNC = "chat.dm_synced_at"
    }
}
