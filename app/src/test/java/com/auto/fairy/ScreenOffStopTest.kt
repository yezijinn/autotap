package com.auto.fairy

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 灭屏停止回放的判定：只有「灭屏 + 回放未空闲」才停。
 *
 * 误停的代价是用户手机一锁屏就丢正在跑的任务；该停不停则是往黑屏上盲点。
 */
class ScreenOffStopTest {

    @Test
    fun `灭屏且回放未空闲要停`() {
        assertTrue(ScreenOffGuard.shouldStop(Intent.ACTION_SCREEN_OFF, idle = false))
    }

    @Test
    fun `空闲时灭屏不停`() {
        assertFalse(ScreenOffGuard.shouldStop(Intent.ACTION_SCREEN_OFF, idle = true))
    }

    /** 亮屏、解锁等一律不管：不做自动恢复，也不该借机停掉刚起的回放。 */
    @Test
    fun `非灭屏事件不停`() {
        assertFalse(ScreenOffGuard.shouldStop(Intent.ACTION_SCREEN_ON, idle = false))
        assertFalse(ScreenOffGuard.shouldStop(null, idle = false))
    }
}
