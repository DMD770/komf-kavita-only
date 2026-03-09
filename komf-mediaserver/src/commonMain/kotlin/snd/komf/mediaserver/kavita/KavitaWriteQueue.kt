package snd.komf.mediaserver.kavita

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class KavitaWriteQueue {
    private val mutex = Mutex()

    suspend fun <T> execute(block: suspend () -> T): T = mutex.withLock { block() }
}

