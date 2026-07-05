package com.inkwell.diary.ink

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

interface ScheduledCommit {
    fun cancel()
}

interface CommitScheduler {
    fun schedule(delayMillis: Long, block: () -> Unit): ScheduledCommit
}

class CoroutineCommitScheduler(
    private val scope: CoroutineScope,
) : CommitScheduler {
    override fun schedule(delayMillis: Long, block: () -> Unit): ScheduledCommit {
        val job = scope.launch {
            delay(delayMillis)
            block()
        }
        return object : ScheduledCommit {
            override fun cancel() {
                job.cancel()
            }
        }
    }
}

class CommitTimer(
    private val delayMillisProvider: () -> Long,
    private val scheduler: CommitScheduler,
    private val onCommit: () -> Unit,
) {
    private var pending: ScheduledCommit? = null
    private var penDown = false

    fun onPenDown() {
        penDown = true
        cancel()
    }

    fun onStrokeMove() {
        if (penDown) {
            cancel()
        } else {
            schedule()
        }
    }

    fun onPenUp() {
        penDown = false
        schedule()
    }

    fun commitNow() {
        cancel()
        onCommit()
    }

    fun cancel() {
        pending?.cancel()
        pending = null
    }

    private fun schedule() {
        cancel()
        pending = scheduler.schedule(delayMillisProvider()) {
            pending = null
            onCommit()
        }
    }
}

