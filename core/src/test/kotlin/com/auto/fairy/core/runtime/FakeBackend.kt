package com.auto.fairy.core.runtime

import com.auto.fairy.core.inject.Caps
import com.auto.fairy.core.inject.Health
import com.auto.fairy.core.inject.InputBackend

/**
 * JVM 测试用后端：不碰设备，按计划绝对时刻等待并记录每个笔画的实际执行时刻。
 *
 * [StrokeRecord.expectedNs] 来自引擎写入的 `Slice.absoluteStartNs`（计划值），
 * 所以 `actualNs - expectedNs` 是端到端时序偏差（含引擎唤醒延迟）。
 */
class FakeBackend(
    override val caps: Caps = Caps(),
) : InputBackend {

    data class StrokeRecord(
        val ordinal: Int,
        val cycle: Long,
        val strokeStartMs: Long,
        val expectedNs: Long,
        val actualNs: Long,
    ) {
        val deviationNs: Long get() = actualNs - expectedNs
    }

    private val lock = Any()
    private val strokeRecords = ArrayList<StrokeRecord>()
    private var nextOrdinal = 0

    /** 指定切片下标返回失败（-1 表示不注入失败）。 */
    @Volatile
    var failOnSlice: Int = -1

    override suspend fun prepare(): Result<Unit> = Result.success(Unit)

    override suspend fun execute(slice: Slice): Result<Unit> {
        val plannedStartNs = slice.absoluteStartNs
        if (slice.index == failOnSlice) {
            return Result.failure(IllegalStateException("注入的失败: slice #${slice.index}"))
        }
        for (stroke in slice.strokes) {
            val expectedNs = plannedStartNs + stroke.startMs * NANOS_PER_MS
            DeadlineTimer.awaitUntil(expectedNs)
            val actualNs = System.nanoTime()
            synchronized(lock) {
                strokeRecords.add(
                    StrokeRecord(
                        ordinal = nextOrdinal++,
                        cycle = slice.cycle,
                        strokeStartMs = stroke.startMs,
                        expectedNs = expectedNs,
                        actualNs = actualNs,
                    ),
                )
            }
            // 按契约占住本笔画的时长，再等下一笔。
            val endNs = plannedStartNs + stroke.endMs * NANOS_PER_MS
            DeadlineTimer.awaitUntil(endNs)
        }

        // 契约：阻塞到片尾，绝不提前返回。
        DeadlineTimer.awaitUntil(plannedStartNs + slice.durationMs * NANOS_PER_MS)
        return Result.success(Unit)
    }

    override fun health(): Health = Health.ALIVE

    fun strokeRecords(): List<StrokeRecord> = synchronized(lock) { strokeRecords.toList() }

    fun reset() {
        synchronized(lock) {
            strokeRecords.clear()
            nextOrdinal = 0
        }
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}
