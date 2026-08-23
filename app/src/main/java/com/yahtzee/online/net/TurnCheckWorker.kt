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
import com.yahtzee.online.game.GameState
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
        val tracked = ActiveGamesStore.all(applicationContext)
        if (tracked.isEmpty()) {
            // Nothing left to watch: stand the job down rather than waking every quarter hour to
            // discover the same thing.
            cancel(applicationContext)
            return Result.success()
        }
        if (!TurnNotifier.canNotify(applicationContext)) return Result.success()

        val repository = GameRepository(applicationContext)
        val myId = repository.localPlayerId

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

    private suspend fun readRoom(repository: GameRepository, code: String): GameState? =
        suspendCancellableCoroutine { continuation ->
            repository.readRoomOnce(code) { state ->
                if (continuation.isActive) continuation.resume(state)
            }
        }
}
