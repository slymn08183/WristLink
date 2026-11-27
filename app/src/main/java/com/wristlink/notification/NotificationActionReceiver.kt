package com.wristlink.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.wristlink.ancs.AncsConstants
import com.wristlink.call.WatchCalls

/**
 * Receives notification action broadcasts (accept/reject/dismiss)
 * and forwards them to the ANCS action callback.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotifActionReceiver"

        const val ACTION_PERFORM = "com.wristlink.ACTION_PERFORM"

        /** IntArray of ANCS UIDs, e.g. every message of a conversation that was swiped away. */
        const val EXTRA_NOTIFICATION_UIDS = "notification_uids"

        /**
         * The ANCS session the UIDs belong to (NotificationRenderer.session). iOS reuses UIDs in
         * the next session, so an action from an older one would hit a different notification.
         * Intents without it (calls) count as current.
         */
        const val EXTRA_SESSION = "ancs_session"

        /**
         * Set this callback to handle notification actions.
         * Called with (notificationUid, actionId, session or null when the intent didn't say).
         */
        var onAction: ((UInt, Byte, Int?) -> Unit)? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PERFORM) return

        val actionId = intent.getByteExtra("action_id", -1)

        // Answer from the call notification: with the audio on the watch when it can be
        if (intent.getBooleanExtra("is_call", false) &&
            actionId == AncsConstants.ACTION_POSITIVE &&
            WatchCalls.answer(context)
        ) return

        val uids = intent.getIntArrayExtra(EXTRA_NOTIFICATION_UIDS)
            ?: intArrayOf(intent.getIntExtra("notification_uid", -1))
        val session = if (intent.hasExtra(EXTRA_SESSION)) intent.getIntExtra(EXTRA_SESSION, 0) else null

        for (uid in uids) {
            if (uid == -1) continue
            Log.d(TAG, "Action received: uid=$uid actionId=$actionId session=$session")
            onAction?.invoke(uid.toUInt(), actionId, session)
        }
    }
}
