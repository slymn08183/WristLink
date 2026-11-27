package com.wristlink.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.wristlink.R
import com.wristlink.ancs.AncsAttributeParser
import com.wristlink.ancs.AncsConstants
import com.wristlink.ancs.AncsNotificationEvent
import com.wristlink.settings.SettingsManager

/**
 * Renders ANCS notifications as Wear OS native notifications.
 * Maps ANCS categories to appropriate channels, icons, and priorities.
 * Handles notification creation, update, and removal.
 *
 * - Notifications are grouped per iPhone app (bundle ID), so ten WhatsApp messages stack
 *   into one WhatsApp group instead of ten loose cards.
 * - Chats (Social category with a sender and a message): every message of one conversation
 *   shares a single notification, newest message on top.
 */
class NotificationRenderer(
    private val context: Context,
    private val settings: SettingsManager
) {

    companion object {
        private const val TAG = "NotificationRenderer"
        /**
         * Card IDs are handed out from here up, clear of the app's fixed notification IDs
         * (service, calls, workout, timers).
         */
        private const val FIRST_CARD_ID = 100_000
        private const val LAST_CARD_ID = 999_999
        private const val GROUP_PREFIX = "ios:"

        /** Older messages of a busy chat are still on the iPhone; the watch keeps it short. */
        private const val MAX_MESSAGES_SHOWN = 10
    }

    private val notificationManager =
        context.getSystemService(NotificationManager::class.java)

    /** One chat thread; all its ANCS notifications share a single Android notification. */
    private class Conversation(
        val key: String,
        val notifId: Int,
        val group: AppGroup,
        /** Group chat name, or null for a 1:1 chat. */
        val title: String?
    ) {
        /**
         * Keyed by the UID (as a Long) for messages of the current session, and by a negative
         * placeholder for messages kept from an earlier session (their UIDs mean nothing now).
         */
        val messages = LinkedHashMap<Long, ChatMessage>()
        lateinit var latestEvent: AncsNotificationEvent
        lateinit var latestAttrs: AncsAttributeParser.NotificationAttributes

        /** UIDs of this session's messages: the ones actions may name. */
        val currentUids: List<UInt> get() = messages.keys.filter { it >= 0 }.map { it.toUInt() }
    }

    private class ChatMessage(val sender: String, val text: String, val timestamp: Long, val fingerprint: String)

    /** Notifications of one iPhone app, stacked under a group summary. */
    private class AppGroup(val key: String, val summaryId: Int) {
        /** Android notification IDs currently in the group. */
        val members = mutableSetOf<Int>()
        var appName: String = ""
        var appIcon: Bitmap? = null
        var categoryId: Byte = AncsConstants.CATEGORY_OTHER
    }

    /** A notification with its own card (not a chat), and the app group it's in. */
    private class Card(val notifId: Int, val group: AppGroup?, val fingerprint: String?)

    // Called from the pipeline's coroutines and BLE callbacks, so all state is behind a lock
    private val lock = Any()
    private val groups = mutableMapOf<String, AppGroup>()
    private val conversations = mutableMapOf<String, Conversation>()
    private val uidToConversation = mutableMapOf<UInt, Conversation>()

    /** This session's cards by UID. */
    private val cards = mutableMapOf<UInt, Card>()

    /**
     * Cards still on the watch from an earlier session. iOS numbers notifications anew per
     * session, so these have no UID until the iPhone lists them again as pre-existing.
     */
    private val staleCards = mutableListOf<Card>()
    private var nextStaleKey = -1L
    private var nextCardId = FIRST_CARD_ID

    /**
     * The ANCS session the shown notifications' UIDs belong to. Goes into every action intent:
     * an action from an older session would name a UID iOS has since given to another
     * notification, so it is ignored (AncsNotificationPipeline).
     */
    @Volatile
    var session: Int = 0
        private set

    /**
     * A new ANCS session starts: this session's notifications stay on screen but lose their
     * UIDs. The iPhone lists what it still has as pre-existing right after subscribing, and
     * those are matched back ([showFullNotification], [adoptStale]); [sweepStale] removes the
     * rest, which were cleared on the iPhone meanwhile. Returns whether anything is stale.
     */
    fun startNewSession(newSession: Int): Boolean = synchronized(lock) {
        session = newSession
        staleCards += cards.values
        cards.clear()
        uidToConversation.clear()
        for (conversation in conversations.values) {
            val entries = conversation.messages.entries.map { it.key to it.value }
            conversation.messages.clear()
            entries.forEach { (key, message) ->
                conversation.messages[if (key >= 0) nextStaleKey-- else key] = message
            }
        }
        hasStaleLocked()
    }

    fun hasStale(): Boolean = synchronized(lock) { hasStaleLocked() }

    private fun hasStaleLocked() =
        staleCards.isNotEmpty() || conversations.values.any { c -> c.messages.keys.any { it < 0 } }

    /**
     * The iPhone has listed everything it still has: what's left from earlier sessions was
     * cleared there, so it goes here too.
     */
    fun sweepStale(forSession: Int) {
        synchronized(lock) {
            if (session != forSession) return
            val touched = mutableSetOf<AppGroup>()
            var removed = 0
            staleCards.forEach { card ->
                notificationManager.cancel(card.notifId)
                card.group?.let { leaveGroup(it, card.notifId); touched += it }
                removed++
            }
            staleCards.clear()
            for (conversation in conversations.values.toList()) {
                val staleKeys = conversation.messages.keys.filter { it < 0 }
                if (staleKeys.isEmpty()) continue
                removed += staleKeys.size
                staleKeys.forEach { conversation.messages.remove(it) }
                touched += conversation.group
                if (conversation.messages.isEmpty()) {
                    conversations.remove(conversation.key)
                    notificationManager.cancel(conversation.notifId)
                    leaveGroup(conversation.group, conversation.notifId)
                } else {
                    postConversation(conversation, alert = false)
                }
            }
            // Summaries still carry the old UIDs in their swipe action
            touched.filter { it.members.isNotEmpty() }.forEach { postGroupSummary(it) }
            if (removed > 0) Log.i(TAG, "Removed $removed notifications cleared on the iPhone while away")
        }
    }

    /**
     * The iPhone never sent this notification's content (even after retries). Still let the
     * user know something arrived.
     */
    fun showUnavailableNotification(event: AncsNotificationEvent) {
        if (event.isSilent) return

        synchronized(lock) {
            // Real content made it after all (e.g. a late response): keep it
            val uid = event.notificationUid
            if (uid in uidToConversation || uid in cards) return

            val builder = NotificationCompat.Builder(context, channelFor(event.categoryId))
                .setSmallIcon(NotificationIcons.iconForCategory(event.categoryId))
                .setContentTitle(event.categoryName)
                .setContentText("Open your phone to read it")
                .setAutoCancel(true)

            builder.priority = NotificationCompat.PRIORITY_HIGH

            val notifId = allocateCardId()
            notificationManager.notify(notifId, builder.build())
            cards[uid] = Card(notifId, group = null, fingerprint = null)
        }
    }

    /**
     * Update a notification with full attributes fetched from ANCS Data Source.
     */
    fun showFullNotification(
        event: AncsNotificationEvent,
        attrs: AncsAttributeParser.NotificationAttributes,
        appDisplayName: String?,
        appIcon: Bitmap?
    ) {
        synchronized(lock) {
            renderFullNotification(event, attrs, appDisplayName, appIcon, adoptOnly = false)
        }
    }

    /**
     * A pre-existing notification the user doesn't want shown (filters, Do Not Disturb, or
     * pre-existing ones off): if it is one still on the watch from an earlier session, give that
     * card its new UID back, quietly, so swiping it and its removal on the iPhone work again.
     */
    fun adoptStale(event: AncsNotificationEvent, attrs: AncsAttributeParser.NotificationAttributes) {
        synchronized(lock) {
            renderFullNotification(event, attrs, appDisplayName = null, appIcon = null, adoptOnly = true)
        }
    }

    private fun renderFullNotification(
        event: AncsNotificationEvent,
        attrs: AncsAttributeParser.NotificationAttributes,
        appDisplayName: String?,
        appIcon: Bitmap?,
        adoptOnly: Boolean
    ) {
        val uid = event.notificationUid
        val appId = attrs.appIdentifier
        val sender = attrs.title
        val text = attrs.message
        val conversationKey = if (
            appId != null &&
            event.categoryId == AncsConstants.CATEGORY_SOCIAL &&
            !sender.isNullOrEmpty() &&
            !text.isNullOrEmpty()
        ) {
            // In group chats iOS puts the sender in the title and the chat name in the subtitle
            "$GROUP_PREFIX$appId|${attrs.subtitle?.takeIf { it.isNotEmpty() } ?: sender}"
        } else {
            null
        }

        // Still on the watch from an earlier session? Only the iPhone's list of what it already
        // has (pre-existing) is matched, so a new notification that happens to read the same
        // still alerts. One the user swiped away meanwhile stays away (that swipe couldn't reach
        // the iPhone).
        val fingerprint = fingerprint(attrs)
        val staleMessageKey = if (event.isPreExisting && conversationKey != null) {
            conversations[conversationKey]
                ?.takeIf { isShowing(it.notifId) }
                ?.messages?.entries
                ?.firstOrNull { it.key < 0 && it.value.fingerprint == fingerprint }?.key
        } else null
        val staleCard = if (event.isPreExisting && staleMessageKey == null) {
            staleCards.firstOrNull { it.fingerprint == fingerprint }?.let { card ->
                if (isShowing(card.notifId)) card else {
                    staleCards.remove(card)
                    card.group?.let { leaveGroup(it, card.notifId) }
                    null
                }
            }
        } else null
        if (adoptOnly && staleMessageKey == null && staleCard == null) return

        val group = appId?.let {
            val group = groups.getOrPut(GROUP_PREFIX + it) {
                AppGroup(GROUP_PREFIX + it, stableNotifId("summary|$it"))
            }
            if (!adoptOnly) {
                group.appName = appDisplayName ?: event.categoryName
                if (appIcon != null) group.appIcon = appIcon
                group.categoryId = event.categoryId
            }
            group
        }

        // A modified notification may have moved to another conversation, or stopped being a chat
        uidToConversation[uid]?.let { existing ->
            if (existing.key != conversationKey) removeChatMessage(uid, existing)
        }
        // The conversation card replaces this message's own card, if it had one
        if (conversationKey != null) removeCard(uid)

        if (conversationKey != null && group != null && sender != null && text != null) {
            showChatMessage(event, attrs, group, conversationKey, sender, text, fingerprint, staleMessageKey)
        } else {
            val name = if (adoptOnly) group?.appName else appDisplayName
            val icon = if (adoptOnly) group?.appIcon else appIcon
            showStandalone(event, attrs, group, name, icon, fingerprint, staleCard)
        }
        group?.let { postGroupSummary(it) }
    }

    private fun showStandalone(
        event: AncsNotificationEvent,
        attrs: AncsAttributeParser.NotificationAttributes,
        group: AppGroup?,
        appDisplayName: String?,
        appIcon: Bitmap?,
        fingerprint: String,
        staleCard: Card?
    ) {
        val uid = event.notificationUid
        val existing = cards[uid]
        val notifId = staleCard?.notifId ?: existing?.notifId ?: allocateCardId()
        if (staleCard != null) staleCards.remove(staleCard)

        val title = buildTitle(event, attrs, appDisplayName)
        val body = buildBody(attrs)

        val builder = NotificationCompat.Builder(context, channelFor(event.categoryId))
            .setSmallIcon(NotificationIcons.iconForCategory(event.categoryId))
            .setContentTitle(title)
            .setAutoCancel(true)
            .setWhen(attrs.timestampMillis ?: System.currentTimeMillis())
            .setShowWhen(true)

        // The small icon has to stay a monochrome silhouette (the system tints it),
        // so the iPhone app's real, full-color icon goes in the large icon slot.
        if (appIcon != null) {
            builder.setLargeIcon(appIcon)
        }
        if (appDisplayName != null && !sameName(appDisplayName, title)) {
            builder.setSubText(appDisplayName)
        }
        if (group != null) {
            builder.setGroup(group.key)
        }

        if (body.isNotEmpty()) {
            builder.setContentText(body)
            // Use BigTextStyle for longer messages
            if (body.length > 40) {
                builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
            }
        }

        builder.priority = NotificationCompat.PRIORITY_HIGH
        builder.setCategory(NotificationCompat.CATEGORY_MESSAGE)
        // Back from an earlier session: already seen, so no second buzz
        if (staleCard != null) builder.setOnlyAlertOnce(true)

        val uids = listOf(uid)
        builder.setDeleteIntent(createActionIntent(notifId, "dismiss", uids, AncsConstants.ACTION_NEGATIVE))
        addActions(builder, event, attrs, notifId, positiveUids = uids, negativeUids = uids)

        notificationManager.notify(notifId, builder.build())
        listOfNotNull(existing?.group, staleCard?.group)
            .filter { it !== group }
            .forEach { leaveGroup(it, notifId) }
        group?.members?.add(notifId)
        cards[uid] = Card(notifId, group, fingerprint)
        Log.d(TAG, "Showed notification uid=$uid${if (staleCard != null) " (kept from before)" else ""}: $title")
    }

    private fun showChatMessage(
        event: AncsNotificationEvent,
        attrs: AncsAttributeParser.NotificationAttributes,
        group: AppGroup,
        key: String,
        sender: String,
        text: String,
        fingerprint: String,
        staleMessageKey: Long?
    ) {
        val uid = event.notificationUid
        val conversation = conversations.getOrPut(key) {
            Conversation(
                key = key,
                notifId = stableNotifId(key),
                group = group,
                title = attrs.subtitle?.takeIf { it.isNotEmpty() }
            )
        }
        staleMessageKey?.let { conversation.messages.remove(it) }
        conversation.messages[uid.toLong()] = ChatMessage(
            sender, text, attrs.timestampMillis ?: System.currentTimeMillis(), fingerprint
        )
        conversation.latestEvent = event
        conversation.latestAttrs = attrs
        uidToConversation[uid] = conversation

        // A message back from an earlier session was seen already: no second buzz
        postConversation(conversation, alert = staleMessageKey == null)
        group.members += conversation.notifId
        Log.d(TAG, "Added uid=$uid to conversation ${conversation.key} (${conversation.messages.size} messages)")
    }

    private fun postConversation(conversation: Conversation, alert: Boolean) {
        val group = conversation.group
        val messages = conversation.messages.values
            .sortedBy { it.timestamp }
            .takeLast(MAX_MESSAGES_SHOWN)
        val latest = messages.last()

        // Samsung's MessagingStyle screen spends half the display on the title, a "Today"
        // divider and the sender's name again above every bubble. Plain big text is just the
        // title, the time and the messages, newest on top; group chats name each sender.
        val isGroup = conversation.title != null
        fun line(m: ChatMessage) = if (isGroup) "${m.sender}: ${m.text}" else m.text
        val title = conversation.title ?: latest.sender
        val style = NotificationCompat.BigTextStyle()
            .bigText(messages.asReversed().joinToString("\n\n") { line(it) })

        // Only this session's UIDs: older ones may name other notifications by now
        val allUids = conversation.currentUids
        val notifId = conversation.notifId
        val builder = NotificationCompat.Builder(context, channelFor(group.categoryId))
            .setSmallIcon(NotificationIcons.iconForCategory(AncsConstants.CATEGORY_SOCIAL))
            .setStyle(style)
            .setContentTitle(title)
            .setContentText(line(latest))
            .setLargeIcon(group.appIcon)
            .setWhen(latest.timestamp)
            .setShowWhen(true)
            .setGroup(group.key)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            // Re-posting after a message was removed on the iPhone shouldn't buzz again
            .setOnlyAlertOnce(!alert)
            // Swiping the conversation away clears all its messages on the iPhone
            .setDeleteIntent(
                createActionIntent(notifId, "dismiss", allUids, AncsConstants.ACTION_NEGATIVE)
            )

        // A sender named like the app (WhatsApp's own messages) would show "WhatsApp" twice
        if (!sameName(group.appName, title)) {
            builder.setSubText(group.appName)
        }

        val latestUid = conversation.messages.entries
            .filter { it.key >= 0 }
            .maxByOrNull { it.value.timestamp }
            ?.key?.toUInt()
        if (latestUid != null) {
            addActions(
                builder, conversation.latestEvent, conversation.latestAttrs, notifId,
                positiveUids = listOf(latestUid),
                negativeUids = allUids
            )
        }

        notificationManager.notify(notifId, builder.build())
    }

    private fun removeChatMessage(uid: UInt, conversation: Conversation) {
        conversation.messages.remove(uid.toLong())
        uidToConversation.remove(uid)
        if (conversation.messages.isEmpty()) {
            conversations.remove(conversation.key)
            notificationManager.cancel(conversation.notifId)
            leaveGroup(conversation.group, conversation.notifId)
        } else {
            postConversation(conversation, alert = false)
        }
    }

    private fun postGroupSummary(group: AppGroup) {
        // Swiping the whole group away clears every notification in it on the iPhone
        val groupUids = cards.filterValues { it.group === group }.keys +
            uidToConversation.filterValues { it.group === group }.keys

        val builder = NotificationCompat.Builder(context, channelFor(group.categoryId))
            .setSmallIcon(NotificationIcons.iconForCategory(group.categoryId))
            .setContentTitle(group.appName)
            .setLargeIcon(group.appIcon)
            .setGroup(group.key)
            .setGroupSummary(true)
            .setAutoCancel(true)
            // Only the children buzz; the summary just holds the stack together
            .setSilent(true)
            .setDeleteIntent(
                createActionIntent(
                    group.summaryId, "dismiss", groupUids.toList(), AncsConstants.ACTION_NEGATIVE
                )
            )

        notificationManager.notify(group.summaryId, builder.build())
    }

    /** The group object itself is kept (one per app), so membership never gets split. */
    private fun leaveGroup(group: AppGroup, notifId: Int) {
        group.members.remove(notifId)
        if (group.members.isEmpty()) {
            notificationManager.cancel(group.summaryId)
        }
    }

    /**
     * Positive/negative buttons from iOS (e.g. "Accept"/"Decline", "Clear").
     */
    private fun addActions(
        builder: NotificationCompat.Builder,
        event: AncsNotificationEvent,
        attrs: AncsAttributeParser.NotificationAttributes,
        notifId: Int,
        positiveUids: List<UInt>,
        negativeUids: List<UInt>
    ) {
        if (event.hasPositiveAction) {
            val label = attrs.positiveActionLabel ?: "Accept"
            val intent = createActionIntent(
                notifId, "positive", positiveUids, AncsConstants.ACTION_POSITIVE
            )
            builder.addAction(
                NotificationCompat.Action.Builder(R.drawable.ic_launcher, label, intent).build()
            )
        }

        if (event.hasNegativeAction) {
            val label = attrs.negativeActionLabel ?: "Dismiss"
            val intent = createActionIntent(
                notifId, "negative", negativeUids, AncsConstants.ACTION_NEGATIVE
            )
            builder.addAction(
                NotificationCompat.Action.Builder(R.drawable.ic_launcher, label, intent).build()
            )
        }
    }

    /**
     * Remove a notification when ANCS sends a Removed event.
     */
    fun cancelNotification(notificationUid: UInt) {
        synchronized(lock) {
            uidToConversation[notificationUid]?.let { conversation ->
                removeChatMessage(notificationUid, conversation)
                return
            }

            removeCard(notificationUid)
        }
    }

    private fun removeCard(uid: UInt) {
        val card = cards.remove(uid) ?: return
        notificationManager.cancel(card.notifId)
        card.group?.let { leaveGroup(it, card.notifId) }
    }

    /**
     * What identifies a notification across sessions: the app and the content iOS sent, with its
     * posting time (to the second), since the UID changes.
     */
    private fun fingerprint(attrs: AncsAttributeParser.NotificationAttributes): String =
        listOf(attrs.appIdentifier, attrs.title, attrs.subtitle, attrs.message, attrs.date)
            .joinToString("\u0001") { it.orEmpty() }

    private fun isShowing(notifId: Int): Boolean =
        runCatching { notificationManager.activeNotifications.any { it.id == notifId } }.getOrDefault(true)

    /**
     * A free ID for a card. Not derived from the UID: iOS reuses UIDs in the next session, and the
     * new notification would replace an unrelated one still shown from before. IDs in use by
     * this app (including ones left from before a restart) are skipped.
     */
    private fun allocateCardId(): Int {
        val inUse = HashSet<Int>()
        runCatching { notificationManager.activeNotifications.forEach { inUse += it.id } }
        cards.values.forEach { inUse += it.notifId }
        staleCards.forEach { inUse += it.notifId }
        while (true) {
            val id = nextCardId
            nextCardId = if (nextCardId >= LAST_CARD_ID) FIRST_CARD_ID else nextCardId + 1
            if (id !in inUse) return id
        }
    }

    private fun channelFor(categoryId: Byte): String =
        NotificationChannels.channelForCategory(categoryId, vibrate = settings.isVibrationEnabled)

    private fun buildTitle(
        event: AncsNotificationEvent,
        attrs: AncsAttributeParser.NotificationAttributes,
        appDisplayName: String?
    ): String {
        val title = attrs.title
        val subtitle = attrs.subtitle

        return when {
            // For calls, use category name as prefix
            event.categoryId == AncsConstants.CATEGORY_INCOMING_CALL ->
                "Incoming Call: ${title ?: "Unknown"}"
            event.categoryId == AncsConstants.CATEGORY_MISSED_CALL ->
                "Missed Call: ${title ?: "Unknown"}"
            // For other notifications, use app name + title
            !title.isNullOrEmpty() && !subtitle.isNullOrEmpty() ->
                "$title - $subtitle"
            !title.isNullOrEmpty() -> title
            appDisplayName != null -> appDisplayName
            else -> event.categoryName
        }
    }

    /** iOS wraps some names in invisible direction marks ("‎WhatsApp"). */
    private fun sameName(a: String, b: String) =
        a.trim('‎', '‏') == b.trim('‎', '‏')

    private fun buildBody(attrs: AncsAttributeParser.NotificationAttributes): String {
        return attrs.message ?: ""
    }

    private fun createActionIntent(
        notifId: Int,
        actionType: String,
        notificationUids: List<UInt>,
        actionId: Byte
    ): PendingIntent {
        val intent = Intent(NotificationActionReceiver.ACTION_PERFORM).apply {
            setPackage(context.packageName)
            // Keeps every notification's PendingIntent distinct (extras alone don't), without
            // affecting how the receiver's intent filter matches
            identifier = "$notifId/$actionType"
            putExtra(
                NotificationActionReceiver.EXTRA_NOTIFICATION_UIDS,
                notificationUids.map { it.toInt() }.toIntArray()
            )
            putExtra(NotificationActionReceiver.EXTRA_SESSION, session)
            putExtra("action_id", actionId)
        }
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    @Suppress("DEPRECATION")
    internal fun wakeScreen() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wasInteractive = pm.isInteractive
        Log.d(TAG, "wakeScreen() called — screen interactive=$wasInteractive")

        try {
            val wakeLock = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK
                    or PowerManager.ACQUIRE_CAUSES_WAKEUP
                    or PowerManager.ON_AFTER_RELEASE,
                "wristlink:notification_wake"
            )
            wakeLock.acquire(3000L)
            Log.d(TAG, "wakeScreen() wake lock acquired — screen now interactive=${pm.isInteractive}")
        } catch (e: Exception) {
            Log.e(TAG, "wakeScreen() wake lock failed", e)
        }

        // Vibrate — short buzz
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            Log.e(TAG, "wakeScreen() vibrate failed", e)
        }
    }

    /** Conversation and summary IDs: negative, so they never collide with card IDs. */
    private fun stableNotifId(key: String): Int = key.hashCode() or Int.MIN_VALUE
}
