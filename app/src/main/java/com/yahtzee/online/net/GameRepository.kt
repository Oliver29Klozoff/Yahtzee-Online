package com.yahtzee.online.net

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.yahtzee.online.bot.BotStrategy
import com.yahtzee.online.bot.LocalGameEngine
import com.yahtzee.online.bot.ExpertStrategy
import com.yahtzee.online.game.Category
import com.yahtzee.online.game.Chat
import com.yahtzee.online.game.ChatMessage
import com.yahtzee.online.game.DiceRoller
import com.yahtzee.online.game.MAX_ROLLS_PER_TURN
import com.yahtzee.online.game.Nudge
import com.yahtzee.online.game.GameState
import com.yahtzee.online.game.Player
import com.yahtzee.online.game.RoomCode
import com.yahtzee.online.game.ScoreKey
import com.yahtzee.online.game.Scoring
import com.yahtzee.online.game.Tournament
import com.yahtzee.online.game.diceAreYahtzee
import com.yahtzee.online.game.grandTotalAllCards
import com.yahtzee.online.game.isClosedToNewPlayers
import com.yahtzee.online.game.yahtzeeBonusUnlocked
import com.yahtzee.online.game.scoresForCard
import java.util.UUID
import com.yahtzee.online.game.PlayerProfile
import com.yahtzee.online.game.TurnOrder
import kotlin.random.Random

class GameRepository(private val context: android.content.Context) {

    private val db = FirebaseDatabase.getInstance()
    private val roller = DiceRoller()
    /**
     * This device's seat at any table, stable for as long as the app is installed.
     *
     * Was a fresh [UUID] per repository instance, which only worked because the id was handed
     * from screen to screen through intent extras: the moment the process died, the player's
     * identity died with it, and reopening a game in progress would have seated them as a
     * stranger alongside their own abandoned player. Reusing the profile id — already persisted
     * for the leaderboard — means a game can be left and come back to, which is the whole
     * premise of playing a turn at a time.
     */
    val localPlayerId: String = PlayerProfile.getId(context)

    private fun roomRef(code: String) = db.getReference("games").child(code)

    /**
     * Stamps a room as still being used, which is the only thing [RoomCleanup] has to go on.
     *
     * Not called on every write — that would be a second round trip per die roll for no gain.
     * It marks the points at which a room's life demonstrably continues: someone opened it,
     * someone sat down, someone finished a turn. A game people are actually playing is stamped
     * at least once a turn; one that was abandoned stops being stamped the moment it was
     * abandoned, which is exactly when the clock on it should start.
     */
    private fun touch(code: String) {
        roomRef(code).child("updatedAt").setValue(System.currentTimeMillis())
    }

    /**
     * Opens a room.
     *
     * [desiredCode] is a code the host asked for. Null takes a generated one, which is retried
     * until it finds a code nobody is using — the write is a plain set on a key, so a code
     * already in use would have been overwritten and the game under it lost. Rare at thirty-three
     * million combinations, certain the moment people start choosing their own and several of
     * them choose GAME.
     *
     * A desired code that is already taken is refused rather than joined: the host asked to make
     * a room, and quietly seating them in a stranger's game is not that. [onResult] is handed an
     * empty string, which is the only way it ever reports one.
     */
    fun createRoom(
        hostName: String,
        diceColor: Int,
        cardCount: Int = 1,
        turnSeconds: Int = 30,
        scorepad: Boolean = false,
        desiredCode: String? = null,
        onResult: (String) -> Unit
    ) {
        if (desiredCode != null && !RoomCode.isValid(desiredCode)) {
            onResult("")
            return
        }
        claimCode(desiredCode, GENERATE_ATTEMPTS) { code ->
            if (code.isEmpty()) onResult("") else writeRoom(code, hostName, diceColor, cardCount, turnSeconds, scorepad, onResult)
        }
    }

    /**
     * Finds a code nobody is using, or confirms the one asked for is free.
     *
     * A generated code gets a handful of tries before giving up; a chosen one gets exactly the
     * one it asked for.
     */
    private fun claimCode(desired: String?, attemptsLeft: Int, onResult: (String) -> Unit) {
        if (attemptsLeft <= 0) {
            onResult("")
            return
        }
        val candidate = desired ?: generateRoomCode()
        roomRef(candidate).child("roomCode").get()
            .addOnSuccessListener { snapshot ->
                when {
                    !snapshot.exists() -> onResult(candidate)
                    desired != null -> onResult("")
                    else -> claimCode(null, attemptsLeft - 1, onResult)
                }
            }
            // A room that cannot be checked is not a reason to refuse a generated code: the
            // odds of a clash are tiny and a game nobody can start is the worse outcome.
            .addOnFailureListener { onResult(if (desired != null) "" else candidate) }
    }

    private fun writeRoom(
        code: String,
        hostName: String,
        diceColor: Int,
        cardCount: Int,
        turnSeconds: Int,
        scorepad: Boolean,
        onResult: (String) -> Unit
    ) {
        val ref = roomRef(code)
        val host = Player(
            id = localPlayerId,
            name = hostName,
            joinedAt = System.currentTimeMillis(),
            diceColor = diceColor
        )
        val state = GameState(
            roomCode = code,
            hostId = localPlayerId,
            status = GameState.STATUS_LOBBY,
            playerOrder = listOf(localPlayerId),
            players = mapOf(localPlayerId to host),
            cardCount = cardCount,
            turnSeconds = turnSeconds,
            scorepad = scorepad
        )
        ref.setValue(state.toMap()).addOnSuccessListener {
            touch(code)
            onResult(code)
        }
    }

    /**
     * Opens a room that the creator watches rather than plays.
     *
     * The television is the table, not a player: it holds the room open and shows what is
     * happening on it, while every seat belongs to a phone. So this creates the room with nobody
     * in it — no player entry, and an empty turn order that fills as people scan in. The host id
     * still points at the screen, since something has to own the room, but nothing ever looks up
     * a seat for it.
     */
    fun createSpectatorRoom(cardCount: Int = 1, onResult: (String) -> Unit) {
        val code = generateRoomCode()
        val state = GameState(
            roomCode = code,
            hostId = localPlayerId,
            status = GameState.STATUS_LOBBY,
            playerOrder = emptyList(),
            players = emptyMap(),
            cardCount = cardCount,
            // A shared screen is the one place a clock makes no sense: everyone can see whose
            // turn it is, and nobody is waiting on a phone that fell asleep in a pocket.
            turnSeconds = 0
        )
        roomRef(code).setValue(state.toMap()).addOnSuccessListener {
            touch(code)
            onResult(code)
        }
    }

    /**
     * Seats a bot in an online room.
     *
     * The point is the television. A room opened on a TV is a table somebody walks up to, and
     * walking up to it alone used to mean there was no game to play — the start needs two, and
     * the bots lived only in the phone's own solo game, where the big screen never saw them. A
     * bot seated here is an ordinary player: it has a name, a colour and a scorecard, the TV
     * renders it like anybody else, and [BotSeatDriver] plays its turns.
     *
     * The free seat and the free name are both worked out from the database rather than from the
     * caller's copy of it, for the reason the tournament version learned: three quick taps off
     * one stale snapshot all pick the same id and write over each other, and three taps make one
     * bot.
     */
    fun addBot(code: String, onResult: (Boolean) -> Unit = {}) {
        val ref = roomRef(code)
        ref.get().addOnSuccessListener { snapshot ->
            if (snapshot.child("status").getValue(String::class.java) != GameState.STATUS_LOBBY) {
                onResult(false)
                return@addOnSuccessListener
            }
            val players = snapshot.child("players")
            if (players.childrenCount >= MAX_ROOM_PLAYERS) {
                onResult(false)
                return@addOnSuccessListener
            }

            val taken = players.children.mapNotNull { it.key }.toSet()
            val id = Tournament.nextBotId(taken)
            val used = players.children
                .mapNotNull { it.child("name").getValue(String::class.java) }
                .toSet()
            val name = LocalGameEngine.BOT_NAMES.firstOrNull { it !in used }
                ?: LocalGameEngine.BOT_NAMES.random()

            // Spread around the colour wheel from whoever is already seated, so two bots are not
            // rolling the same colour as each other or as the person who added them.
            val hsv = FloatArray(3)
            val seed = players.children
                .mapNotNull { it.child("diceColor").getValue(Int::class.java) }
                .firstOrNull { it != 0 } ?: DEFAULT_SEED_COLOUR
            android.graphics.Color.colorToHSV(seed, hsv)
            val colour = android.graphics.Color.HSVToColor(
                floatArrayOf((hsv[0] + 360f / (MAX_ROOM_PLAYERS + 1) * (taken.size + 1)) % 360f, 0.78f, 0.95f)
            )

            ref.child("players").child(id).setValue(
                Player(id = id, name = name, joinedAt = System.currentTimeMillis(), diceColor = colour)
            )
            ref.child("playerOrder").get().addOnSuccessListener { orderSnap ->
                val order = orderSnap.children.mapNotNull { it.getValue(String::class.java) }.toMutableList()
                if (id !in order) {
                    order.add(id)
                    ref.child("playerOrder").setValue(order)
                }
                touch(code)
                onResult(true)
            }
        }.addOnFailureListener { onResult(false) }
    }

    /** Takes a bot back out again, while the room is still a lobby. */
    fun removeBot(code: String, botId: String) {
        if (!Tournament.isBot(botId)) return
        val ref = roomRef(code)
        ref.get().addOnSuccessListener { snapshot ->
            if (snapshot.child("status").getValue(String::class.java) != GameState.STATUS_LOBBY) {
                return@addOnSuccessListener
            }
            ref.child("players").child(botId).removeValue()
            val order = snapshot.child("playerOrder").children
                .mapNotNull { it.getValue(String::class.java) }
                .filterNot { it == botId }
            ref.child("playerOrder").setValue(order)
            touch(code)
        }
    }

    /**
     * Takes a seat, or says why not.
     *
     * Answers [JOIN_OK], [JOIN_NOT_FOUND] or [JOIN_CLOSED] rather than a yes or no, because
     * "could not join" and "the game has moved on without you" want different things said to the
     * person holding the phone.
     */
    fun joinRoom(code: String, playerName: String, diceColor: Int, onResult: (Int) -> Unit) {
        val ref = roomRef(code)
        ref.get().addOnSuccessListener { snapshot ->
            if (!snapshot.exists()) {
                onResult(JOIN_NOT_FOUND)
                return@addOnSuccessListener
            }
            // Rejoining is not the same as joining. Now that the player id is stable across
            // launches, writing a fresh Player over the top would blank the scorecard of anyone
            // reopening a game they are already in — which is the ordinary way to take a turn in
            // a game played over days. An existing seat therefore keeps its scores and only has
            // the details that may legitimately have changed refreshed on it.
            val existing = snapshot.child("players").child(localPlayerId)

            // Closing the room must never shut out the people already in it. Someone backing out
            // to answer a message and coming back is the ordinary way a long game is played, and
            // they are rejoining a seat rather than taking a new one — so this is checked only
            // for a player the room has never seen.
            if (!existing.exists() && snapshot.toGameState()?.isClosedToNewPlayers() == true) {
                onResult(JOIN_CLOSED)
                return@addOnSuccessListener
            }

            if (existing.exists()) {
                ref.child("players").child(localPlayerId).child("name").setValue(playerName)
                ref.child("players").child(localPlayerId).child("diceColor").setValue(diceColor)
            } else {
                ref.child("players").child(localPlayerId).setValue(
                    Player(
                        id = localPlayerId,
                        name = playerName,
                        joinedAt = System.currentTimeMillis(),
                        diceColor = diceColor
                    )
                )
            }
            // A room opened by a television is owned by a screen that has no seat and no way to
            // press anything, so the first player to actually sit down takes it over. Without
            // this the Start button belongs to the TV and nobody can ever begin the game.
            val currentHost = snapshot.child("hostId").getValue(String::class.java)
            val hostHasSeat = !currentHost.isNullOrEmpty() &&
                snapshot.child("players").child(currentHost).exists()
            if (!hostHasSeat) ref.child("hostId").setValue(localPlayerId)

            ref.child("playerOrder").get().addOnSuccessListener { orderSnap ->
                val order = orderSnap.children.mapNotNull { it.getValue(String::class.java) }.toMutableList()
                if (localPlayerId !in order) {
                    order.add(localPlayerId)
                    ref.child("playerOrder").setValue(order)
                }
                touch(code)
                onResult(JOIN_OK)
            }
        }.addOnFailureListener { onResult(JOIN_NOT_FOUND) }
    }

    /**
     * Puts the dice somebody actually rolled on the table.
     *
     * The scorepad's version of rolling. `rollsUsed` is set to one rather than counted, because a
     * physical roll cannot be counted and the number is only ever asked one question here — has
     * this player got dice down yet, and may they therefore score. Anything that wanted the real
     * count, like the off-the-rip shout, stays quiet in a scorepad room rather than guessing.
     */
    fun setDice(code: String, dice: List<Int>) {
        if (dice.size != 5 || dice.any { it !in 1..6 }) return
        roomRef(code).updateChildren(
            mapOf(
                "dice" to dice,
                "rollsUsed" to 1,
                "held" to List(5) { false }
            )
        )
        touch(code)
    }

    fun listenToRoom(code: String, onUpdate: (GameState?) -> Unit): ValueEventListener {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                onUpdate(snapshot.toGameState())
            }
            override fun onCancelled(error: DatabaseError) {
                onUpdate(null)
            }
        }
        roomRef(code).addValueEventListener(listener)
        return listener
    }

    /**
     * Invites [targetPlayerId] to [code].
     *
     * Written to a node the invited player's own device polls, rather than pushed. That inverts
     * the usual problem: an invite cannot be delivered without a server to send it, but the
     * turn-check job is already visiting Firebase on a timer, so an invite left where that job
     * will find it arrives on exactly the same footing as a turn — no push, no billing, no
     * second mechanism.
     */
    fun invitePlayer(targetPlayerId: String, code: String, fromName: String) {
        if (targetPlayerId.isEmpty() || code.isEmpty()) return
        db.getReference("invites").child(targetPlayerId).child(code).setValue(
            mapOf(
                "fromName" to fromName,
                "fromId" to localPlayerId,
                "createdAt" to System.currentTimeMillis()
            )
        )
    }

    /** Invites waiting for this device, as room code to inviter's name. */
    fun readInvites(onResult: (Map<String, String>) -> Unit) {
        db.getReference("invites").child(localPlayerId).get()
            .addOnSuccessListener { snapshot ->
                onResult(
                    snapshot.children.mapNotNull { child ->
                        val code = child.key ?: return@mapNotNull null
                        val from = child.child("fromName").getValue(String::class.java).orEmpty()
                        code to from
                    }.toMap()
                )
            }
            .addOnFailureListener { onResult(emptyMap()) }
    }

    /**
     * Gives up this device's seat in a room.
     *
     * Only safe before play starts, which is why the caller checks: turn order is a list of
     * player ids and the current turn is an index into it, so pulling a name out of the middle
     * of a game in progress would hand somebody else's turn to the wrong person. Once a game is
     * under way the seat stays and the room is only dropped from this device's list.
     *
     * The host leaving passes the room to whoever is left rather than leaving it pointing at
     * nobody, and the last player out takes the room with them instead of leaving an empty one
     * behind for good.
     */
    fun leaveRoom(code: String, onDone: () -> Unit = {}) {
        val ref = roomRef(code)
        ref.get().addOnSuccessListener { snapshot ->
            val order = snapshot.child("playerOrder").children
                .mapNotNull { it.getValue(String::class.java) }
                .filterNot { it == localPlayerId }

            if (order.isEmpty()) {
                ref.removeValue()
                onDone()
                return@addOnSuccessListener
            }

            ref.child("players").child(localPlayerId).removeValue()
            ref.child("playerOrder").setValue(order)
            if (snapshot.child("hostId").getValue(String::class.java) == localPlayerId) {
                ref.child("hostId").setValue(order.first())
            }
            onDone()
        }.addOnFailureListener { onDone() }
    }

    /**
     * Hands the room to another player.
     *
     * Being host is only the right to start the game and call a rematch, so this is not a
     * transfer of anything precious — but the person who scanned in first is not necessarily the
     * person who should be driving, and on a TV game they may not even be the one holding the
     * remote conversation. Written to whoever is named rather than negotiated: the host is the
     * only one who can offer it, so there is nothing to agree.
     */
    fun transferHost(code: String, toPlayerId: String) {
        if (code.isEmpty() || toPlayerId.isEmpty()) return
        roomRef(code).child("hostId").setValue(toPlayerId)
    }

    /**
     * Sends a reaction to everyone in the room.
     *
     * Written under the sender rather than appended to a list: a reaction is a moment, not a
     * record, and one slot per player means a room cannot accumulate history nobody will read.
     * The timestamp is what makes the same emoji twice register as two reactions rather than one.
     */
    fun sendReaction(code: String, emoji: String) {
        if (code.isEmpty() || emoji.isEmpty()) return
        roomRef(code).child("reactions").child(localPlayerId).setValue(
            mapOf("emoji" to emoji, "at" to System.currentTimeMillis())
        )
    }

    /**
     * Says something in the room.
     *
     * Appended under a generated key rather than written to a slot, because a message is a record
     * — the person it is for is very often not looking at their phone when it arrives, which in a
     * game played a turn a day is the normal case rather than the exception.
     *
     * The oldest are pruned once the room passes [Chat.MAX_MESSAGES]. The whole room is re-read on
     * every roll, every held die and every score, so an unbounded history would have a long game
     * re-downloading all of it many times a turn. Pruning is done by whoever is sending, since
     * they are already writing.
     */
    fun sendChat(code: String, text: String) {
        val message = Chat.clean(text) ?: return
        if (code.isEmpty()) return

        val chatRef = roomRef(code).child("chat")
        val key = chatRef.push().key ?: return
        chatRef.child(key).setValue(
            mapOf(
                "senderId" to localPlayerId,
                "senderName" to PlayerProfile.getName(context).ifEmpty { "Player" },
                "text" to message,
                "at" to System.currentTimeMillis()
            )
        ).addOnSuccessListener { pruneChat(code) }
    }

    /**
     * Prods whoever the room is waiting on.
     *
     * Only ever aimed at the current player, and never at yourself — a button that lets you
     * hurry yourself along is just a button that does nothing.
     */
    fun sendNudge(code: String, toPlayerId: String) {
        if (code.isEmpty() || toPlayerId.isEmpty() || toPlayerId == localPlayerId) return
        roomRef(code).child("nudge").setValue(
            mapOf(
                "byName" to PlayerProfile.getName(context).ifEmpty { "Someone" },
                "toPlayerId" to toPlayerId,
                "at" to System.currentTimeMillis()
            )
        )
    }

    /**
     * Takes back something you said.
     *
     * Guarded on the sender here, and only here: the rules cannot tell one player from another
     * within a room, since identity is the local profile id rather than the auth uid. So this is
     * a courtesy for the person who mistyped, not a protection against someone determined — which
     * is the same footing as everything else in a room anyone with the code can walk into.
     */
    fun deleteChat(code: String, message: ChatMessage) {
        if (message.senderId != localPlayerId) return
        roomRef(code).child("chat").child(message.id).removeValue()
    }

    private fun pruneChat(code: String) {
        val chatRef = roomRef(code).child("chat")
        chatRef.get().addOnSuccessListener { snapshot ->
            val entries = snapshot.children.mapNotNull { entry ->
                val id = entry.key ?: return@mapNotNull null
                id to (entry.child("at").getValue(Long::class.java) ?: 0L)
            }
            if (entries.size <= Chat.MAX_MESSAGES) return@addOnSuccessListener
            entries.sortedBy { it.second }
                .take(entries.size - Chat.MAX_MESSAGES)
                .forEach { (id, _) -> chatRef.child(id).removeValue() }
        }
    }

    /** Clears one invite once it has been acted on, so it is not announced twice. */
    fun clearInvite(code: String) {
        db.getReference("invites").child(localPlayerId).child(code).removeValue()
    }

    /**
     * Reads a room once and stops.
     *
     * [listenToRoom] holds an open subscription, which is right for a board on screen and wrong
     * for a background sweep that wants a snapshot of several rooms and then wants to go back to
     * sleep.
     */
    fun readRoomOnce(code: String, onResult: (GameState?) -> Unit) {
        roomRef(code).get()
            .addOnSuccessListener { onResult(it.toGameState()) }
            .addOnFailureListener { onResult(null) }
    }

    fun stopListening(code: String, listener: ValueEventListener) {
        roomRef(code).removeEventListener(listener)
    }

    /**
     * Sends the room to the roll-off, if there is anybody to roll against.
     *
     * The seat count is read from the database rather than taken from the caller's copy of it.
     * The lobby greys its button out on the same rule, but it does so off a snapshot that can be
     * a moment behind — and the failure it is guarding against is a whole game played alone, so
     * it is worth checking twice.
     */
    fun startGame(code: String) {
        val ref = roomRef(code)
        ref.child("players").get().addOnSuccessListener { snapshot ->
            if (snapshot.childrenCount < MIN_PLAYERS_TO_START) return@addOnSuccessListener
            ref.child("status").setValue(GameState.STATUS_ROLL_OFF)
            ref.child("openingRolls").setValue(null)
            ref.child("openingRollTied").setValue(null)
        }
    }

    /** Rolls one die for [playerId] during the pre-game roll-off to decide turn order. */
    fun rollForFirst(code: String, state: GameState, playerId: String) {
        val eligible = if (state.openingRollTied.isNotEmpty()) state.openingRollTied else state.playerOrder
        if (playerId !in eligible) return
        if (state.openingRolls.containsKey(playerId)) return

        val ref = roomRef(code)
        val value = Random.nextInt(1, 7)
        ref.child("openingRolls").child(playerId).setValue(value)

        val updatedRolls = state.openingRolls + (playerId to value)
        if (eligible.all { updatedRolls.containsKey(it) }) {
            resolveRollOff(code, state, updatedRolls, eligible)
        }
    }

    private fun resolveRollOff(code: String, state: GameState, rolls: Map<String, Int>, eligible: List<String>) {
        val ref = roomRef(code)
        val highest = eligible.maxOf { rolls[it] ?: 0 }
        val winners = eligible.filter { rolls[it] == highest }

        if (winners.size > 1) {
            // Tie: reset and let only the tied players roll again.
            ref.child("openingRollTied").setValue(winners)
            ref.child("openingRolls").setValue(null)
            return
        }

        val firstPlayerId = winners.first()
        // Play starts at the winner and carries on around the table from there — nobody changes
        // seats just because someone else won the toss.
        val reordered = TurnOrder.startingWith(state.playerOrder, firstPlayerId)
        ref.child("playerOrder").setValue(reordered)
        ref.child("currentTurnIndex").setValue(0)
        ref.child("status").setValue(GameState.STATUS_PLAYING)
        ref.child("dice").setValue(List(5) { 1 })
        ref.child("held").setValue(List(5) { false })
        ref.child("rollsUsed").setValue(0)
        ref.child("turnDeadline").setValue(deadlineFor(state))
        ref.child("openingRolls").setValue(null)
        ref.child("openingRollTied").setValue(null)
    }

    /**
     * A fresh deadline for this room, or 0 when the host turned the clock off — the timer UI
     * treats 0 as "no limit" and hides itself, and auto-play never fires.
     */
    private fun deadlineFor(state: GameState): Long =
        if (state.turnMillis <= 0L) 0L else System.currentTimeMillis() + state.turnMillis

    /**
     * @param turnMillis this room's turn length, used to restart the clock.
     * @param resetTimer restarts the turn clock, so each roll a player makes buys them a fresh
     * turn's worth of thinking time. Automatic rolls pass false: an abandoned turn that kept
     * extending its own deadline would take minutes to resolve instead of finishing.
     */
    fun rollDice(
        code: String,
        currentDice: List<Int>,
        held: List<Boolean>,
        rollsUsed: Int,
        turnMillis: Long = 0L,
        resetTimer: Boolean = true
    ) {
        if (rollsUsed >= 3) return
        val heldSet = held.mapIndexedNotNull { i, isHeld -> if (isHeld) i else null }.toSet()
        val newDice = roller.reroll(currentDice, heldSet)
        val ref = roomRef(code)
        ref.child("dice").setValue(newDice)
        ref.child("rollsUsed").setValue(rollsUsed + 1)
        if (resetTimer && turnMillis > 0L) {
            ref.child("turnDeadline").setValue(System.currentTimeMillis() + turnMillis)
        }
    }

    /**
     * Gives the player whose turn it is their time back.
     *
     * For a phone that has just woken up to find the clock already run down. The turn clock is
     * there to stop an absent player holding the room up, but it is enforced by that player's own
     * device — so a phone that dozed through somebody else's turn came back, saw a deadline long
     * past, and auto-played the turn of somebody who was standing right there holding it.
     *
     * Extending rather than suspending: the room still gets its protection against a player who
     * really has gone, they simply have to be given a fair chance to take the turn first.
     */
    fun extendTurn(code: String, turnMillis: Long) {
        if (turnMillis <= 0L) return
        roomRef(code).child("turnDeadline").setValue(System.currentTimeMillis() + turnMillis)
    }

    fun toggleHold(code: String, held: List<Boolean>, index: Int) {
        val updated = held.toMutableList()
        updated[index] = !updated[index]
        roomRef(code).child("held").setValue(updated)
    }

    /**
     * Sets the whole hold pattern at once.
     *
     * For a player choosing all five at a stroke rather than tapping dice one at a time — which
     * is what a bot does, and what makes its keep visible on the table before it rerolls.
     */
    fun setHeld(code: String, held: List<Boolean>) {
        if (held.size != 5) return
        roomRef(code).child("held").setValue(held)
    }

    fun submitScore(
        code: String,
        state: GameState,
        category: Category,
        playerId: String,
        cardIndex: Int = 0
    ) {
        val player = state.players[playerId] ?: return
        val key = ScoreKey.of(cardIndex, category)
        if (player.scores.containsKey(key)) return

        val points = Scoring.score(category, state.dice)
        val ref = roomRef(code)

        // Every extra Yahtzee after the box holds 50 is worth another 100 points. A bonus is
        // earned only if a Yahtzee is already banked as one somewhere; with several cards in
        // play that can be on any of them.
        val earnedBonus = category != Category.YAHTZEE &&
            state.diceAreYahtzee() &&
            player.yahtzeeBonusUnlocked(state.cardCount)

        val nextIndex = (state.currentTurnIndex + 1) % state.playerOrder.size

        // The whole hand-off in one write, and this has to stay that way.
        //
        // These used to go out as four: the turn index, then the held flags, then the roll count,
        // then the deadline. Every one of them is delivered to every client on its own, so there
        // was a moment on the wire where the room said it was the next player's turn while the
        // roll count still read three from the turn that had just ended. A person never noticed.
        // A bot did: its driver read that snapshot as its own turn with all three rolls spent,
        // and decided to score without rolling at all.
        //
        // Leave `dice` as whatever they last showed (per-player preference) — only held and the
        // roll count reset, so the dice visually stay put until someone actually rolls again.
        val updates = mutableMapOf<String, Any?>(
            "players/$playerId/scores/$key" to points,
            "currentTurnIndex" to nextIndex,
            "held" to List(5) { false },
            "rollsUsed" to 0,
            "turnDeadline" to deadlineFor(state),
            // A completed turn is the clearest possible sign the room is alive.
            "updatedAt" to System.currentTimeMillis()
        )
        if (earnedBonus) {
            updates["players/$playerId/yahtzeeBonusCount"] = player.yahtzeeBonusCount + 1
        }
        ref.updateChildren(updates)

        val allDone = state.players.values.all {
            val scores = if (it.id == playerId) it.scores + (key to points) else it.scores
            scores.size == state.totalSlots
        }
        if (allDone) {
            val winner = state.players.values.maxByOrNull {
                // Include a bonus earned on this very play: the write above has not round-tripped
                // through Firebase yet, so `state` still holds the pre-bonus count and a
                // game-winning extra Yahtzee would otherwise be left out of the comparison.
                val withLatest = if (it.id == playerId) {
                    it.copy(
                        scores = it.scores + (key to points),
                        yahtzeeBonusCount = it.yahtzeeBonusCount + if (earnedBonus) 1 else 0
                    )
                } else it
                withLatest.grandTotalAllCards(state.cardCount)
            }
            // One write, not two.
            //
            // Written separately, the status arrived first and the winner a moment later, so every
            // client — including this one — got a snapshot that said the game was over and did not
            // yet say who had won. The game-over dialog fires on exactly that snapshot and is
            // guarded against firing twice, so it read the empty id, showed "Winner: ?", and never
            // corrected itself when the name landed a beat later.
            ref.updateChildren(
                mapOf(
                    "status" to GameState.STATUS_FINISHED,
                    "winnerId" to (winner?.id ?: "")
                )
            )
        }
    }

    /**
     * Called by the current player's own client when their turn timer expires. Rolls for
     * them if they still have rolls left, otherwise scores their best available category
     * automatically so the game keeps moving instead of stalling on an inactive player.
     */
    fun autoPlayTurn(code: String, state: GameState, playerId: String) {
        if (!state.isMyTurn(playerId)) return
        val player = state.players[playerId] ?: return
        val cards = state.cardCount.coerceAtLeast(1)

        val openByCard = (0 until cards)
            .associateWith { card ->
                Category.values().filter { !player.scores.containsKey(ScoreKey.of(card, it)) }.toSet()
            }
            .filterValues { it.isNotEmpty() }
        if (openByCard.isEmpty()) return

        // Use the remaining rolls before settling, keeping whatever best serves a still-open
        // category. This reuses the bot's own strategy rather than a naive reroll, so an
        // abandoned turn still plays a sensible hand instead of scoring the first thing rolled.
        if (state.rollsUsed < MAX_ROLLS_PER_TURN) {
            if (state.rollsUsed == 0) {
                rollDice(code, state.dice, List(5) { false }, 0, state.turnMillis, resetTimer = false)
                return
            }
            val rollsLeft = MAX_ROLLS_PER_TURN - state.rollsUsed
            // Auto-play covers for an absent human, so it plays the best hand available rather
            // than a difficulty setting meant to make an opponent beatable.
            val upperBest = (0 until state.cardCount.coerceAtLeast(1)).maxOfOrNull { card ->
                Category.UPPER.sumOf { player.scoresForCard(card)[it] ?: 0 }
            } ?: 0
            val holds = ExpertStrategy.chooseHolds(
                state.dice,
                openByCard.values.flatten().toSet(),
                rollsLeft,
                upperBest
            )
            if (holds.size < 5) {
                val heldFlags = List(5) { it in holds }
                roomRef(code).child("held").setValue(heldFlags)
                rollDice(code, state.dice, heldFlags, state.rollsUsed, state.turnMillis, resetTimer = false)
                return
            }
            // Holding all five means the strategy is content; fall through and score.
        }

        // Score the best slot available, judged per card by the same category logic the bots use
        // so high-value boxes are not burned on a poor roll.
        val best = openByCard.entries
            .map { (card, open) ->
                val upperTotal = Category.UPPER.sumOf { player.scoresForCard(card)[it] ?: 0 }
                val category = BotStrategy.chooseCategory(state.dice, open, upperTotal)
                Triple(card, category, Scoring.score(category, state.dice))
            }
            .maxByOrNull { it.third }
            ?: return
        submitScore(code, state, best.second, playerId, best.first)
    }

    /**
     * Replays the same room with the same people: clears every scorecard and returns to the
     * roll-off, so first turn is decided fresh rather than inherited from the previous game.
     *
     * Keeps the room code, the players, their colours and the chosen format, which is the whole
     * point — a rematch should not mean everyone rejoining.
     */
    fun rematch(code: String, state: GameState) {
        val ref = roomRef(code)
        state.players.keys.forEach { id ->
            ref.child("players").child(id).child("scores").setValue(null)
            ref.child("players").child(id).child("yahtzeeBonusCount").setValue(0)
        }
        ref.child("winnerId").setValue("")
        ref.child("currentTurnIndex").setValue(0)
        ref.child("rollsUsed").setValue(0)
        ref.child("held").setValue(List(5) { false })
        ref.child("turnDeadline").setValue(0L)
        ref.child("openingRolls").setValue(null)
        ref.child("openingRollTied").setValue(null)
        // Status goes last: every client watches this to decide when to move, so flipping it
        // before the reset had landed would send them into a game still holding old scores.
        ref.child("status").setValue(GameState.STATUS_ROLL_OFF)
    }

    /**
     * Deletes a room outright. Used when the host abandons a lobby nobody joined — leaving it
     * behind would litter the database with empty rooms and hold on to its code.
     */
    fun deleteRoom(code: String) {
        roomRef(code).removeValue()
    }

    private fun generateRoomCode(): String = RoomCode.random()

    companion object {
        /**
         * An online room is a game against somebody; one seat filled is not a game.
         *
         * Bots count. Seating one is how a person at a television gets a game out of a table
         * they walked up to alone.
         */
        const val MIN_PLAYERS_TO_START = 2

        /** As many as the table can seat and the TV can draw without the names collapsing. */
        const val MAX_ROOM_PLAYERS = 6

        const val JOIN_OK = 0
        const val JOIN_NOT_FOUND = 1

        /** The game has moved on: everybody in it has taken a turn. */
        const val JOIN_CLOSED = 2

        /** How many generated codes to try before giving up on finding a free one. */
        private const val GENERATE_ATTEMPTS = 5

        /** Cobalt, the app's default die, for a room where nobody has picked a colour yet. */
        private const val DEFAULT_SEED_COLOUR = 0xFF1F4FD8.toInt()
    }
}

private fun GameState.toMap(): Map<String, Any?> = mapOf(
    "roomCode" to roomCode,
    "hostId" to hostId,
    "status" to status,
    "playerOrder" to playerOrder,
    "players" to players.mapValues { it.value.toMap() },
    "currentTurnIndex" to currentTurnIndex,
    "rollsUsed" to rollsUsed,
    "dice" to dice,
    "held" to held,
    "winnerId" to winnerId,
    "turnDeadline" to turnDeadline,
    "openingRolls" to openingRolls,
    "openingRollTied" to openingRollTied,
    "cardCount" to cardCount,
    "turnSeconds" to turnSeconds,
    "scorepad" to scorepad
)

private fun Player.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "name" to name,
    "joinedAt" to joinedAt,
    "scores" to scores,
    "yahtzeeBonusCount" to yahtzeeBonusCount,
    // Was missing, which silently dropped the HOST's dice colour: joining writes the Player
    // object directly and so carried it, but room creation goes through this map.
    "diceColor" to diceColor
)

private fun DataSnapshot.toGameState(): GameState? {
    if (!exists()) return null
    val roomCode = child("roomCode").getValue(String::class.java) ?: return null
    val hostId = child("hostId").getValue(String::class.java) ?: ""
    val status = child("status").getValue(String::class.java) ?: GameState.STATUS_LOBBY
    val playerOrder = child("playerOrder").children.mapNotNull { it.getValue(String::class.java) }
    val players = child("players").children.mapNotNull { playerSnap ->
        val id = playerSnap.child("id").getValue(String::class.java) ?: return@mapNotNull null
        val name = playerSnap.child("name").getValue(String::class.java) ?: ""
        val joinedAt = playerSnap.child("joinedAt").getValue(Long::class.java) ?: 0L
        val scores = playerSnap.child("scores").children.mapNotNull scoreLoop@{ scoreSnap ->
            val key = scoreSnap.key ?: return@scoreLoop null
            val value = scoreSnap.getValue(Int::class.java) ?: return@scoreLoop null
            key to value
        }.toMap()
        val bonus = playerSnap.child("yahtzeeBonusCount").getValue(Int::class.java) ?: 0
        val diceColor = playerSnap.child("diceColor").getValue(Int::class.java) ?: 0
        id to Player(id, name, joinedAt, scores, bonus, diceColor)
    }.toMap()
    val currentTurnIndex = child("currentTurnIndex").getValue(Int::class.java) ?: 0
    val rollsUsed = child("rollsUsed").getValue(Int::class.java) ?: 0
    val dice = child("dice").children.mapNotNull { it.getValue(Int::class.java) }
        .ifEmpty { listOf(1, 1, 1, 1, 1) }
    val held = child("held").children.mapNotNull { it.getValue(Boolean::class.java) }
        .ifEmpty { List(5) { false } }
    val winnerId = child("winnerId").getValue(String::class.java) ?: ""
    val turnDeadline = child("turnDeadline").getValue(Long::class.java) ?: 0L
    val openingRolls = child("openingRolls").children.mapNotNull { rollSnap ->
        val key = rollSnap.key ?: return@mapNotNull null
        val value = rollSnap.getValue(Int::class.java) ?: return@mapNotNull null
        key to value
    }.toMap()
    val openingRollTied = child("openingRollTied").children.mapNotNull { it.getValue(String::class.java) }
    val reactions = child("reactions").children.mapNotNull { entry ->
        val id = entry.key ?: return@mapNotNull null
        val emoji = entry.child("emoji").getValue(String::class.java) ?: return@mapNotNull null
        id to (emoji to (entry.child("at").getValue(Long::class.java) ?: 0L))
    }.toMap()
    val cardCount = child("cardCount").getValue(Int::class.java) ?: 1
    val turnSeconds = child("turnSeconds").getValue(Int::class.java) ?: 30
    val scorepad = child("scorepad").getValue(Boolean::class.java) ?: false
    // Sorted here rather than trusted: Firebase orders children by key, and the push ids these
    // use do sort chronologically, but the reading is not worth leaving to a property of the
    // key format when the timestamp is right there.
    val chat = child("chat").children.mapNotNull { entry ->
        val id = entry.key ?: return@mapNotNull null
        val text = entry.child("text").getValue(String::class.java) ?: return@mapNotNull null
        ChatMessage(
            id = id,
            senderId = entry.child("senderId").getValue(String::class.java).orEmpty(),
            senderName = entry.child("senderName").getValue(String::class.java).orEmpty(),
            text = text,
            at = entry.child("at").getValue(Long::class.java) ?: 0L
        )
    }.sortedBy { it.at }

    val nudge = child("nudge").takeIf { it.exists() }?.let { entry ->
        val to = entry.child("toPlayerId").getValue(String::class.java)
        if (to.isNullOrEmpty()) null else Nudge(
            byName = entry.child("byName").getValue(String::class.java).orEmpty(),
            toPlayerId = to,
            at = entry.child("at").getValue(Long::class.java) ?: 0L
        )
    }

    return GameState(
        roomCode, hostId, status, playerOrder, players,
        currentTurnIndex, rollsUsed, dice, held, winnerId, turnDeadline,
        openingRolls, openingRollTied, cardCount, turnSeconds, reactions, chat, nudge, scorepad
    )
}
