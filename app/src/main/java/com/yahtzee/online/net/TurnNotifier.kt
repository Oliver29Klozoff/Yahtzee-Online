package com.yahtzee.online.net

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.yahtzee.online.R
import com.yahtzee.online.ui.SplashActivity

/**
 * Raises the "it's your turn" notification.
 *
 * Local rather than pushed. Sending a push from one player's phone to another needs a server to
 * do the sending — the client-side send API was withdrawn, and the alternative, Cloud Functions,
 * needs a billing plan attached to the Firebase project. A periodic check that posts its own
 * notification needs neither and works with the app closed; the cost is that a turn can sit for
 * up to the check interval before the player hears about it.
 */
object TurnNotifier {

    private const val CHANNEL_ID = "your_turn"

    /**
     * Ids are derived from the room code so a second notification for the same game replaces the
     * first rather than stacking another copy of the same news.
     */
    private fun notificationId(roomCode: String): Int = roomCode.hashCode()

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_your_turn),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.channel_your_turn_description)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /** Whether notifications may actually be posted — permission is refusable from Android 13. */
    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun notifyYourTurn(context: Context, roomCode: String, opponentCount: Int) {
        if (!canNotify(context)) return
        ensureChannel(context)

        // Opens the app at the splash, which routes on to the menu; the game is reached from the
        // games list there. Deliberately not deep-linked into the board: the room may have moved
        // on between the notification being posted and the player tapping it.
        val intent = Intent(context, SplashActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context,
            notificationId(roomCode),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stats)
            .setContentTitle(context.getString(R.string.notify_your_turn_title))
            .setContentText(context.getString(R.string.notify_your_turn_text, roomCode, opponentCount))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId(roomCode), notification)
        }
    }

    fun notifyInvite(context: Context, roomCode: String, fromName: String) {
        if (!canNotify(context)) return
        ensureChannel(context)

        val intent = Intent(context, SplashActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context,
            notificationId(roomCode),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stats)
            .setContentTitle(context.getString(R.string.notify_invite_title, fromName))
            .setContentText(context.getString(R.string.notify_invite_text, roomCode))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId(roomCode), notification)
        }
    }

    /**
     * Someone else in a duel has posted their score.
     *
     * A duel is silent by construction — you play your round and then nothing happens, possibly
     * for a day, because the other person is doing theirs whenever suits them. Without this the
     * result would only ever be found by opening the app and going to look, which is asking the
     * player to poll on the app's behalf.
     *
     * Opens straight into the duel rather than the start screen: the notification is about one
     * specific result, and landing anywhere else makes the reader go and find it.
     */
    fun notifyDuelResult(context: Context, duelCode: String, fromName: String) {
        if (!canNotify(context)) return
        ensureChannel(context)

        val intent = Intent(context, SplashActivity::class.java)
            .setData(android.net.Uri.parse("yahtzee://duel/$duelCode"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context,
            notificationId("duel:$duelCode"),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stats)
            .setContentTitle(context.getString(R.string.duel_result_ready, fromName))
            .setContentText(context.getString(R.string.duel_result_ready_body, duelCode))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(notificationId("duel:$duelCode"), notification)
        }
    }

    fun clear(context: Context, roomCode: String) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(notificationId(roomCode))
        }
    }
}
