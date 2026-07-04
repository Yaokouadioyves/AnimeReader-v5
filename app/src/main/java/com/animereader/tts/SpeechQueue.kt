package com.animereader.tts

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One line waiting to be read aloud. [id] must match the utterance id the providers report. */
data class SpeechItem(
    val id: String,
    val text: String,
    val langTag: String,
    val rate: Float,
    val timbre: String,
    val original: String = text,
    val translated: String? = null,
)

/**
 * Sequential utterance queue: a newly detected bubble no longer cuts the voice mid-sentence,
 * it waits its turn. Completion is signalled by the providers' onSpeechDone callbacks
 * (via [signalDone]); a safety timeout keeps the queue moving if a signal is lost.
 * Pure coroutines (no android.*) so it is JVM-unit-testable.
 */
class SpeechQueue(
    private val scope: CoroutineScope,
    private val speak: suspend (SpeechItem) -> Unit,
    private val onItemStart: (SpeechItem) -> Unit = {},
    private val maxQueued: Int = 4,
    private val doneTimeoutMs: Long = 30_000,
) {
    private val lock = Any()
    private val queue = ArrayDeque<SpeechItem>()
    private var worker: Job? = null
    private var currentDone: CompletableDeferred<Unit>? = null

    /** Id of the item being spoken right now, or null between items. */
    @Volatile var currentId: String? = null
        private set

    /** Queue [item]; the oldest *waiting* item is dropped when the backlog exceeds [maxQueued]. */
    fun enqueue(item: SpeechItem) {
        synchronized(lock) {
            queue.addLast(item)
            while (queue.size > maxQueued) queue.removeFirst()
        }
        ensureWorker()
    }

    /** Called from the voice providers' completion callbacks. */
    fun signalDone(id: String?) {
        val done = synchronized(lock) { if (id != null && id == currentId) currentDone else null }
        done?.complete(Unit)
    }

    /** Drop everything waiting and release the current item (stop/pause/language change). */
    fun clear() {
        synchronized(lock) {
            queue.clear()
            currentDone?.complete(Unit)
        }
    }

    private fun ensureWorker() {
        synchronized(lock) {
            if (worker?.isActive == true) return
            worker = scope.launch {
                while (true) {
                    val item: SpeechItem
                    val done = CompletableDeferred<Unit>()
                    synchronized(lock) {
                        val next = queue.removeFirstOrNull()
                        if (next == null) {
                            currentId = null; currentDone = null
                            return@launch
                        }
                        item = next
                        currentId = item.id; currentDone = done
                    }
                    onItemStart(item)
                    runCatching { speak(item) }
                    withTimeoutOrNull(doneTimeoutMs) { done.await() }
                    synchronized(lock) {
                        if (currentId == item.id) { currentId = null; currentDone = null }
                    }
                }
            }
        }
    }
}
