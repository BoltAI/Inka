package com.inkwell.diary.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitTimerTest {
    @Test
    fun `pen up schedules commit at configured delay`() {
        val scheduler = FakeCommitScheduler()
        var commits = 0
        val timer = CommitTimer({ 2200L }, scheduler) { commits++ }

        timer.onPenDown()
        timer.onStrokeMove()
        timer.onPenUp()

        assertEquals(2200L, scheduler.tasks.single().delayMillis)
        assertEquals(0, commits)

        scheduler.fireNext()

        assertEquals(1, commits)
    }

    @Test
    fun `new stroke cancels previous pending commit`() {
        val scheduler = FakeCommitScheduler()
        var commits = 0
        val timer = CommitTimer({ 2000L }, scheduler) { commits++ }

        timer.onPenDown()
        timer.onPenUp()
        val first = scheduler.tasks.single()
        timer.onPenDown()
        timer.onPenUp()

        assertTrue(first.cancelled)
        assertEquals(2, scheduler.tasks.size)

        scheduler.fireNext()

        assertEquals(1, commits)
        assertFalse(scheduler.tasks.last().cancelled)
    }
}

private class FakeCommitScheduler : CommitScheduler {
    val tasks = mutableListOf<FakeTask>()

    override fun schedule(delayMillis: Long, block: () -> Unit): ScheduledCommit {
        return FakeTask(delayMillis, block).also { tasks.add(it) }
    }

    fun fireNext() {
        tasks.firstOrNull { !it.cancelled }?.block?.invoke()
    }
}

private class FakeTask(
    val delayMillis: Long,
    val block: () -> Unit,
) : ScheduledCommit {
    var cancelled = false

    override fun cancel() {
        cancelled = true
    }
}

