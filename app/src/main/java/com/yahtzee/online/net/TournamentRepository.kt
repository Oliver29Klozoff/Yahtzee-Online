package com.yahtzee.online.net

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.yahtzee.online.bot.LocalGameEngine
import com.yahtzee.online.game.Entrant
import com.yahtzee.online.game.Match
import com.yahtzee.online.game.PlayerProfile
import com.yahtzee.online.game.Tournament
import com.yahtzee.online.game.TournamentState
import kotlin.random.Random

/**
 * The shared half of a tournament: the draw everybody is looking at.
 *
 * Its own node rather than a field on a room, because a tournament outlives every room in it. The
 * bracket is the thing people come back to between matches, and each match is an ordinary game
 * created when its two players are ready — which is what keeps the whole feature on top of the
 * game that already exists rather than beside it.
 */
class TournamentRepository(private val context: android.content.Context) {

    private val db = FirebaseDatabase.getInstance()

    val localPlayerId: String = PlayerProfile.getId(context)

    private fun ref(code: String) = db.getReference("tournaments").child(code)

    fun create(name: String, hostName: String, cardCount: Int, onResult: (String) -> Unit) {
        val code = generateCode()
        val now = System.currentTimeMillis()
        val host = mapOf(
            "id" to localPlayerId,
            "name" to hostName,
            "joinedAt" to now,
            "seed" to 0
        )
        val payload = mapOf(
            "code" to code,
            "name" to name,
            "hostId" to localPlayerId,
            "status" to Tournament.OPEN,
            "cardCount" to cardCount,
            "createdAt" to now,
            "updatedAt" to now,
            "players" to mapOf(localPlayerId to host)
        )
        ref(code).setValue(payload).addOnSuccessListener { onResult(code) }
            .addOnFailureListener { onResult("") }
    }

    /**
     * Opens a tournament the creator runs but does not play in.
     *
     * The television's version, and the same arrangement its rooms already use: the screen holds
     * the bracket open and shows it to everybody, while every entrant is a phone. The host id
     * points at the screen because something has to own the draw, but no seat is made for it —
     * a television standing in its own bracket would be an entrant nobody could play against.
     *
     * The first phone to enter takes the tournament over, exactly as it takes over a room,
     * because starting the draw is the host's to do and a TV cannot press anything.
     */
    fun createSpectator(name: String, cardCount: Int, onResult: (String) -> Unit) {
        val code = generateCode()
        val now = System.currentTimeMillis()
        val payload = mapOf(
            "code" to code,
            "name" to name,
            "hostId" to localPlayerId,
            "status" to Tournament.OPEN,
            "cardCount" to cardCount,
            "createdAt" to now,
            "updatedAt" to now
        )
        ref(code).setValue(payload).addOnSuccessListener { onResult(code) }
            .addOnFailureListener { onResult("") }
    }

    /**
     * Takes a seat, or says why not.
     *
     * The seed is the number of people already in, which is join order — there is nothing else to
     * go on the first time a group plays, and it is at least stable. Rejoining keeps the seed you
     * already had, so opening the bracket twice does not move you in the draw.
     */
    fun join(code: String, playerName: String, onResult: (Int) -> Unit) {
        ref(code).get().addOnSuccessListener { snapshot ->
            if (!snapshot.exists()) {
                onResult(JOIN_NOT_FOUND)
                return@addOnSuccessListener
            }
            val players = snapshot.child("players")
            val already = players.child(localPlayerId)
            if (!already.exists()) {
                if (snapshot.child("status").getValue(String::class.java) != Tournament.OPEN) {
                    onResult(JOIN_STARTED)
                    return@addOnSuccessListener
                }
                if (players.childrenCount >= Tournament.MAX_PLAYERS) {
                    onResult(JOIN_FULL)
                    return@addOnSuccessListener
                }
            }
            val seed = already.child("seed").getValue(Int::class.java)
                ?: players.childrenCount.toInt()
            players.ref.child(localPlayerId).setValue(
                mapOf(
                    "id" to localPlayerId,
                    "name" to playerName,
                    "joinedAt" to System.currentTimeMillis(),
                    "seed" to seed
                )
            )
            // A tournament opened by a television belongs to a screen with no seat and no way to
            // press anything, so the first entrant to actually sit down takes it over. Without
            // this the draw could never be started and the bracket would never be made.
            val currentHost = snapshot.child("hostId").getValue(String::class.java)
            val hostHasSeat = !currentHost.isNullOrEmpty() && players.child(currentHost).exists()
            if (!hostHasSeat) ref(code).child("hostId").setValue(localPlayerId)

            touch(code)
            onResult(JOIN_OK)
        }.addOnFailureListener { onResult(JOIN_NOT_FOUND) }
    }

    /**
     * Seats a bot, so a short field can still make a bracket.
     *
     * The free seat is worked out from the database rather than from the caller's copy of it.
     * Tapping the button three times in a second is the obvious way to add three bots, and off a
     * snapshot that had not caught up yet all three picked the same id and wrote over each other —
     * three taps, one bot.
     */
    fun addBot(code: String) {
        ref(code).child("players").get().addOnSuccessListener { snapshot ->
            val taken = snapshot.children.mapNotNull { it.key }.toSet()
            val id = Tournament.nextBotId(taken)
            // Named from the same pool the solo game draws from, and the name is carried into the
            // match when somebody plays it — so the bot in the bracket and the bot across the
            // table are recognisably the same one.
            val used = snapshot.children
                .mapNotNull { it.child("name").getValue(String::class.java) }
                .toSet()
            val name = LocalGameEngine.BOT_NAMES.firstOrNull { it !in used }
                ?: LocalGameEngine.BOT_NAMES.random()

            ref(code).child("players").child(id).setValue(
                mapOf(
                    "id" to id,
                    "name" to name,
                    "joinedAt" to System.currentTimeMillis(),
                    "seed" to taken.size
                )
            )
            touch(code)
        }
    }

    /** Makes the draw and locks the field. Only the host's screen offers this. */
    fun start(state: TournamentState) {
        val matches = Tournament.draw(state.entrants)
        if (matches.isEmpty()) return
        ref(state.code).updateChildren(
            mapOf(
                "status" to Tournament.RUNNING,
                "matches" to matches.mapValues { it.value.toMap() },
                "updatedAt" to System.currentTimeMillis()
            )
        )
    }

    /** Notes which room a match is being played in, so the other player can join the same one. */
    fun claimRoom(code: String, matchId: String, roomCode: String) {
        ref(code).child("matches").child(matchId).updateChildren(
            mapOf("roomCode" to roomCode, "status" to Tournament.MATCH_PLAYING)
        )
        touch(code)
    }

    /**
     * Reports a finished match and moves the bracket on.
     *
     * The whole draw is rewritten rather than the one match, because advancing is a change to the
     * round above as well. Read-then-write rather than a transaction: the two people in a match
     * both report the same result from the same finished game, so the second write says what the
     * first one said. [Tournament.settle] ignores a match that is already decided, which is what
     * makes the second report harmless.
     */
    fun report(code: String, matchId: String, aScore: Int, bScore: Int) {
        ref(code).get().addOnSuccessListener { snapshot ->
            val state = snapshot.toTournament()
            if (state.matches[matchId]?.decided == true) return@addOnSuccessListener

            val seeds = state.players.mapValues { it.value.seed }
            val settled = Tournament.settle(
                state.matches, matchId, aScore, bScore
            ) { id -> seeds[id] ?: Int.MAX_VALUE }

            val done = settled.values.filter { it.round == state.rounds - 1 }.all { it.decided }
            ref(code).updateChildren(
                mapOf(
                    "matches" to settled.mapValues { it.value.toMap() },
                    "status" to if (done) Tournament.DONE else Tournament.RUNNING,
                    "updatedAt" to System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * The same as [report], for a caller that knows the scores but not which seat is which.
     *
     * A room does not know which of its players was drawn on the top half of a fixture, so the
     * scores have to be read after the match is fetched rather than before. [scores] is handed the
     * two seat ids in draw order and answers with their totals in the same order.
     */
    fun reportFrom(code: String, matchId: String, scores: (String, String) -> Pair<Int, Int>) {
        ref(code).child("matches").child(matchId).get().addOnSuccessListener { snapshot ->
            val aId = snapshot.child("aId").getValue(String::class.java).orEmpty()
            val bId = snapshot.child("bId").getValue(String::class.java).orEmpty()
            if (aId.isEmpty() || bId.isEmpty()) return@addOnSuccessListener
            val (a, b) = scores(aId, bId)
            report(code, matchId, a, b)
        }
    }

    /**
     * Watches a tournament. [onMissing] fires only when the server says there is nothing there.
     *
     * Kept separate from a null state on purpose. A tournament that has been deleted and one this
     * phone simply cannot reach right now look identical if both arrive as null, and treating the
     * second as the first would drop somebody out of a live tournament the moment their signal
     * dipped. Only a snapshot that actually came back empty counts as gone.
     */
    fun listen(
        code: String,
        onMissing: () -> Unit = {},
        onState: (TournamentState?) -> Unit
    ): ValueEventListener {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    onMissing()
                    return
                }
                onState(snapshot.toTournament())
            }

            override fun onCancelled(error: DatabaseError) = onState(null)
        }
        ref(code).addValueEventListener(listener)
        return listener
    }

    fun stopListening(code: String, listener: ValueEventListener) {
        ref(code).removeEventListener(listener)
    }

    private fun touch(code: String) {
        ref(code).child("updatedAt").setValue(System.currentTimeMillis())
    }

    private fun generateCode(): String {
        // The same alphabet room codes use: no letters anybody reads back as a digit.
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..5).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    companion object {
        const val JOIN_OK = 0
        const val JOIN_NOT_FOUND = 1
        const val JOIN_FULL = 2
        const val JOIN_STARTED = 3
    }
}

private fun Match.toMap(): Map<String, Any?> = mapOf(
    "round" to round,
    "slot" to slot,
    "aId" to aId,
    "bId" to bId,
    "aScore" to aScore,
    "bScore" to bScore,
    "winnerId" to winnerId,
    "roomCode" to roomCode,
    "status" to status
)

private fun DataSnapshot.toTournament(): TournamentState {
    val players = child("players").children.mapNotNull { entry ->
        val id = entry.key ?: return@mapNotNull null
        id to Entrant(
            id = id,
            name = entry.child("name").getValue(String::class.java).orEmpty(),
            joinedAt = entry.child("joinedAt").getValue(Long::class.java) ?: 0L,
            seed = entry.child("seed").getValue(Int::class.java) ?: 0
        )
    }.toMap()

    val matches = child("matches").children.mapNotNull { entry ->
        val id = entry.key ?: return@mapNotNull null
        id to Match(
            round = entry.child("round").getValue(Int::class.java) ?: 0,
            slot = entry.child("slot").getValue(Int::class.java) ?: 0,
            aId = entry.child("aId").getValue(String::class.java).orEmpty(),
            bId = entry.child("bId").getValue(String::class.java).orEmpty(),
            aScore = entry.child("aScore").getValue(Int::class.java) ?: 0,
            bScore = entry.child("bScore").getValue(Int::class.java) ?: 0,
            winnerId = entry.child("winnerId").getValue(String::class.java).orEmpty(),
            roomCode = entry.child("roomCode").getValue(String::class.java).orEmpty(),
            status = entry.child("status").getValue(String::class.java) ?: Tournament.MATCH_PENDING
        )
    }.toMap()

    return TournamentState(
        code = child("code").getValue(String::class.java).orEmpty(),
        name = child("name").getValue(String::class.java).orEmpty(),
        hostId = child("hostId").getValue(String::class.java).orEmpty(),
        status = child("status").getValue(String::class.java) ?: Tournament.OPEN,
        cardCount = child("cardCount").getValue(Int::class.java) ?: 1,
        createdAt = child("createdAt").getValue(Long::class.java) ?: 0L,
        updatedAt = child("updatedAt").getValue(Long::class.java) ?: 0L,
        players = players,
        matches = matches
    )
}
