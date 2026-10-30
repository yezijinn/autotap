package com.auto.fairy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 播放目标的取值链：小窗名字、坐标层圆点、加号追加、列表高亮全都读它。
 *
 * 这条链一段写错就是「名字写 A、圆点画 B、+ 追加进 C」，因此每段都要单独锁住：
 * 运行中的当前脚本 → 空闲时的上次播放 → 运行过的脚本 → 列表首项兜底。
 */
class PlaybackTargetTest {

    /** 运行中（含暂停）只认正在跑的脚本：上次播放是另一条也不许串。 */
    @Test
    fun `运行中读正在跑的脚本`() {
        assertEquals("running", resolvePlaybackTarget(true, "running", "last", "first"))
        // 暂停时调用方同样传 playing=true，读数不得跳到上次播放
        assertEquals("running", resolvePlaybackTarget(true, "running", null, "first"))
    }

    /** 空闲时以上次播放为准，其次才是运行过的脚本。 */
    @Test
    fun `空闲优先上次播放`() {
        assertEquals("last", resolvePlaybackTarget(false, "current", "last", "first"))
        assertEquals("current", resolvePlaybackTarget(false, "current", null, "first"))
    }

    /** 没有任何记录（首次运行 / 目标被删）才回落列表首项。 */
    @Test
    fun `无记录回落列表首项`() {
        assertEquals("first", resolvePlaybackTarget(false, null, null, "first"))
        assertEquals("first", resolvePlaybackTarget(true, null, "last", "first"))
    }

    /** 有目标时绝不回落：回落只发生在目标缺失之后，否则就是「显示 A 实际播 B」。 */
    @Test
    fun `有目标时不回落列表首项`() {
        assertEquals("current", resolvePlaybackTarget(false, null, "current", "first"))
        assertEquals("last", resolvePlaybackTarget(false, "current", "last", "first"))
    }

    /** 列表也是空的（一条脚本都没有）：目标为 null，调用方据此显示空态。 */
    @Test
    fun `什么都没有时为空`() {
        assertNull(resolvePlaybackTarget(false, null, null, null))
    }

    /** 不做列表回落的调用方（只想知道目标本身）传 null 兜底，语义不变。 */
    @Test
    fun `不传列表兜底时只返回目标本身`() {
        assertEquals("current", resolvePlaybackTarget(false, "current", null, null))
        assertNull(resolvePlaybackTarget(false, null, null, null))
    }
}
