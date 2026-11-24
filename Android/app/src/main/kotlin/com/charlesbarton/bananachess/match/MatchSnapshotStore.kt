package com.charlesbarton.bananachess.match

import com.charlesbarton.bananachess.engine.model.GameState
import com.charlesbarton.bananachess.engine.online.GameStateCodec
import com.charlesbarton.bananachess.engine.online.MatchCodecError
import com.charlesbarton.bananachess.engine.rules.RulesEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The match in progress, as it is put away when the app closes and brought back on Home. */
data class MatchSnapshot(
    val mode: MatchMode,
    val state: GameState,
    val rulesVersion: Int = RulesEngine.RULES_VERSION,
)

/**
 * The one match this phone keeps for resuming. Best-effort throughout: a snapshot that cannot be
 * written or read simply means there is nothing to resume.
 *
 * [save] and [clear] return at once and land in the order they were called, and [load] waits
 * for every write called before it.
 */
interface MatchSnapshotStore {
    /** Replaces the saved match with [snapshot]. */
    fun save(snapshot: MatchSnapshot)

    /** Forgets the saved match, if there is one. */
    fun clear()

    /**
     * The saved match, or null when there is none that can still be played: nothing saved, a
     * match that is over, one saved under other rules, or one that cannot be read. Anything
     * unplayable that was found is deleted.
     */
    suspend fun load(): MatchSnapshot?

    companion object {
        /** Keeps nothing, for previews and for tests that are not about resuming. */
        val None: MatchSnapshotStore =
            object : MatchSnapshotStore {
                override fun save(snapshot: MatchSnapshot) = Unit

                override fun clear() = Unit

                override suspend fun load(): MatchSnapshot? = null
            }
    }
}

/** The file's shape. The state is the engine's own encoding, so there is one encoding of a match. */
@Serializable
private data class StoredSnapshot(
    val rulesVersion: Int,
    val mode: MatchMode,
    val state: JsonElement,
)

/** Turns a snapshot into the file's text and back. */
internal object MatchSnapshotCodec {
    fun encode(snapshot: MatchSnapshot): String =
        Json.encodeToString(
            StoredSnapshot.serializer(),
            StoredSnapshot(
                rulesVersion = snapshot.rulesVersion,
                mode = snapshot.mode,
                state = Json.parseToJsonElement(GameStateCodec.encode(snapshot.state)),
            ),
        )

    /**
     * Null for a snapshot saved under other rules: it is refused, never migrated, because its
     * board was arranged for rules that no longer apply.
     *
     * @throws IllegalArgumentException when [text] is not a snapshot (kotlinx-serialization's
     *   errors are all subclasses of it).
     * @throws MatchCodecError when its state cannot be read.
     */
    fun decode(text: String): MatchSnapshot? {
        val root = Json.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException("not an object")
        // Read first and alone, so a snapshot from other rules is dropped whatever else changed in it.
        val rulesVersion = root["rulesVersion"]?.jsonPrimitive?.int
        if (rulesVersion != RulesEngine.RULES_VERSION) return null
        val stored = Json.decodeFromJsonElement(StoredSnapshot.serializer(), root)
        return MatchSnapshot(stored.mode, GameStateCodec.decode(stored.state.toString()), stored.rulesVersion)
    }
}

/**
 * The snapshot as one JSON file in [directory], written atomically: the new text goes to a
 * temporary file that then replaces the old one, so a crash mid-write leaves the previous match
 * rather than half of one.
 *
 * @param scope where the file is read and written, one operation at a time and in the order they
 *   were asked for. Its dispatcher is the IO dispatcher; when it is cancelled the store stops and
 *   [load] finds nothing.
 */
class FileMatchSnapshotStore(
    private val directory: File,
    scope: CoroutineScope,
    fileName: String = FILE_NAME,
) : MatchSnapshotStore {
    private val file = File(directory, fileName)
    private val temporary = File(directory, "$fileName.tmp")

    private sealed interface Operation {
        data class Save(
            val snapshot: MatchSnapshot,
        ) : Operation

        data object Clear : Operation

        data class Load(
            val result: CompletableDeferred<MatchSnapshot?>,
        ) : Operation
    }

    // One reader works through these in order, so a load never sees a file older than the last
    // save before it, and two saves never land the wrong way round.
    private val operations =
        Channel<Operation>(Channel.UNLIMITED) { undelivered ->
            (undelivered as? Operation.Load)?.result?.complete(null)
        }

    init {
        val reader =
            scope.launch {
                operations.consumeEach { operation ->
                    when (operation) {
                        is Operation.Save -> write(operation.snapshot)
                        Operation.Clear -> delete()
                        is Operation.Load -> operation.result.complete(read())
                    }
                }
            }
        // Also when the scope ended before the reader started, so a load can never wait forever.
        reader.invokeOnCompletion { operations.cancel() }
    }

    override fun save(snapshot: MatchSnapshot) {
        operations.trySend(Operation.Save(snapshot))
    }

    override fun clear() {
        operations.trySend(Operation.Clear)
    }

    override suspend fun load(): MatchSnapshot? {
        val result = CompletableDeferred<MatchSnapshot?>()
        if (operations.trySend(Operation.Load(result)).isFailure) return null
        return result.await()
    }

    // Failures in here are deliberately not shown to the player. Resuming is a convenience: a
    // snapshot that could not be written costs them a restart, and telling them so would only
    // interrupt a match that is otherwise fine. Anything unreadable is deleted so it cannot fail
    // again on every launch.

    private fun write(snapshot: MatchSnapshot) {
        try {
            val text = MatchSnapshotCodec.encode(snapshot)
            directory.mkdirs()
            FileOutputStream(temporary).use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: IOException) {
            temporary.delete()
        }
    }

    private fun read(): MatchSnapshot? {
        if (!file.exists()) return null
        val snapshot =
            try {
                MatchSnapshotCodec.decode(file.readText(Charsets.UTF_8))
            } catch (_: IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: MatchCodecError) {
                null
            }
        if (snapshot == null || snapshot.state.status.isOver) {
            delete()
            return null
        }
        return snapshot
    }

    private fun delete() {
        file.delete()
    }

    companion object {
        const val FILE_NAME = "current-match.json"
    }
}

/** A store that keeps the snapshot in memory, for tests and for a container with no files. */
class InMemoryMatchSnapshotStore(
    initial: MatchSnapshot? = null,
) : MatchSnapshotStore {
    @Volatile
    var snapshot: MatchSnapshot? = initial
        private set

    override fun save(snapshot: MatchSnapshot) {
        this.snapshot = snapshot
    }

    override fun clear() {
        snapshot = null
    }

    override suspend fun load(): MatchSnapshot? {
        val current = snapshot ?: return null
        if (current.rulesVersion != RulesEngine.RULES_VERSION || current.state.status.isOver) {
            snapshot = null
            return null
        }
        return current
    }
}
