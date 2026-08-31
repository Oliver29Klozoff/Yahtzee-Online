package com.yahtzee.online.net

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yahtzee.online.game.ActiveGamesStore
import com.yahtzee.online.game.Duel
import com.yahtzee.online.game.GameState
import com.yahtzee.online.game.NudgeSeen
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Checks the rooms this device is in and raises a notification for any where it is now the
 * player's turn.
 *
 * This is the whole of "async multiplayer" on the client side: the game state already lives in
 * Firebase and already survives everyone closing the app, so what was missing was never the
 * model but a reason to come back — something that tells you your turn has come round while you
 * were doing something else.
 */
class TurnCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val WORK_NAME = "turn_check"

        /**
         * Fifteen minutes is the floor the platform allows for periodic work; asking for less
         * silently gets this anyway. A turn-at-a-time game is the one kind where that latency is
         * unremarkable — the alternative is a server the project does not have.
         */
        private const val INTERVAL_MINUTES = 15L

        /**
         * Starts the watch, or leaves the existing one alone. Safe to call on every launch:
         * KEEP means an already-scheduled job keeps its place in the queue rather than having
         * its interval restarted each time the app opens.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TurnCheckWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES
            ).setConstraints(
                // Nothing to check without a connection, and retrying offline would only burn
                // battery to fail.
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        // Everything below reads or writes, and the database no longer answers to callers without
        // a session. Usually one is already restored from disk and this returns at once; the case
        // worth handling is the run where this job is what started the process, and the sign-in
        // fired from the Application has not landed yet. Retrying beats reporting no turns.
        awaitSignIn()
        if (!FirebaseSignIn.isReady) return Result.retry()

        val repository = GameRepository(applicationContext)
        val myId = repository.localPlayerId

        // Invites first, so a game someone just opened is picked up on the same sweep that would
        // otherwise have found nothing to do and stood the job down.
        collectInvites(repository)

        val tracked = ActiveGamesStore.all(applicationContext)
        val duels = Duel.joined(applicationContext)
        if (tracked.isEmpty() && duels.isEmpty()) {
            // Nothing left to watch: stand the job down rather than waking every quarter hour to
            // discover the same thing.
            cancel(applicationContext)
            return Result.success()
        }
        if (!TurnNotifier.canNotify(applicationContext)) return Result.success()

        checkDuels(duels)

        for (game in tracked) {
            val state = readRoom(repository, game.roomCode) ?: continue

            // A room that has finished or vanished stops being watched. Checked before the turn
            // test so a finished game cannot keep announcing a turn that no longer exists.
            if (state.status == GameState.STATUS_FINISHED || myId !in state.players) {
                ActiveGamesStore.untrack(applicationContext, game.roomCode)
                TurnNotifier.clear(applicationContext, game.roomCode)
                continue
            }

            if (state.status != GameState.STATUS_PLAYING) continue

            // A nudge is somebody actually waiting, so it is announced even if the ordinary
            // your-turn notification for this turn has already been sent and dismissed.
            state.nudge?.let { nudge ->
                if (nudge.toPlayerId == myId &&
                    nudge.at > NudgeSeen.lastSeen(applicationContext, game.roomCode)
                ) {
                    NudgeSeen.mark(applicationContext, game.roomCode, nudge.at)
                    TurnNotifier.notifyNudge(
                        applicationContext,
                        game.roomCode,
                        nudge.byName.ifEmpty { "Someone" }
                    )
                }
            }

            if (state.currentPlayerId != myId) continue

            // Identifies this turn specifically, so the same turn is announced once however many
            // times the job runs before it is taken.
            val turnKey = "${state.currentTurnIndex}:${state.players[myId]?.scores?.size ?: 0}"
            if (game.notifiedTurnKey == turnKey) continue

            TurnNotifier.notifyYourTurn(
                applicationContext,
                game.roomCode,
                state.playerOrder.size - 1
            )
            ActiveGamesStore.markNotified(applicationContext, game.roomCode, turnKey)
        }
        return Result.success()
    }

    /**
     * Turns any waiting invites into tracked games, announcing each once.
     *
     * The invite is cleared as soon as it is taken up: it has done its job by putting the room
     * in the player's list, and leaving it would have every later sweep offer the same game
     * again. Tracking is what matters — the notification is only how they hear about it.
     */
    private suspend fun collectInvites(repository: GameRepository) {
        val invites = suspendCancellableCoroutine<Map<String, String>> { continuation ->
            repository.readInvites { if (continuation.isActive) continuation.resume(it) }
        }
        val alreadyTracked = ActiveGamesStore.all(applicationContext).map { it.roomCode }.toSet()

        invites.forEach { (code, fromName) ->
            repository.clearInvite(code)
            if (code in alreadyTracked) return@forEach
            ActiveGamesStore.track(applicationContext, code)
            TurnNotifier.notifyInvite(applicationContext, code, fromName.ifEmpty { "Someone" })
        }
    }

    /**
     * Announces duels where somebody else has finished since this last looked.
     *
     * Only once this device has played its own round. Before that the player has a duel waiting
     * for *them*, and telling them their opponent has already gone would be pressure rather than
     * news — it is also visible on the start screen, where it belongs.
     */
    private suspend fun checkDuels(codes: List<String>) {
        val repository = DuelRepository(applicationContext)
        codes.forEach { code ->
            val state = readDuel(repository, code) ?: return@forEach

            val me = state.players.firstOrNull { it.id == repository.localPlayerId }
            val others = state.players.filterNot { it.id == repository.localPlayerId }
            val finished = others.count { it.hasPlayed }

            val seen = Duel.seenFinishers(applicationContext, code)
            if (finished <= seen) return@forEach
            Duel.markSeenFinishers(applicationContext, code, finished)

            if (me?.hasPlayed != true) return@forEach
            val newest = others.filter { it.hasPlayed }.maxByOrNull { it.finishedAt } ?: return@forEach
            TurnNotifier.notifyDuelResult(applicationContext, code, newest.name)
        }
    }

    private suspend fun readDuel(repository: DuelRepository, code: String) =
        suspendCancellableCoroutine { continuation ->
            repository.readOnce(code) { if (continuation.isActive) continuation.resume(it) }
        }

    private suspend fun awaitSignIn(): Unit =
        suspendCancellableCoroutine { continuation ->
            FirebaseSignIn.awaitReady { if (continuation.isActive) continuation.resume(Unit) }
        }

    private suspend fun readRoom(repository: GameRepository, code: String): GameState? =
        suspendCancellableCoroutine { continuation ->
            repository.readRoomOnce(code) { state ->
                if (continuation.isActive) continuation.resume(state)
            }
        }
}
