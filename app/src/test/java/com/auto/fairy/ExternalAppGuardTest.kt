package com.auto.fairy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外部应用守卫：播放（含暂停）期间切到别的应用必须停，从本包切到目标 App 不能误停。
 *
 * 误停的代价是用户一开播就断；该停不停则是脚本在别人的界面上按老坐标乱点。
 */
class ExternalAppGuardTest {

    private val self = "com.blackberry.keyboard"
    private val game = "com.example.game"
    private val chat = "com.example.chat"
    private val ime = "com.example.ime"

    /** 起播时前台已经是目标应用：直接锁定它，同包事件不算切换。 */
    @Test
    fun `起播时前台外部应用即目标`() {
        val guard = ExternalAppGuard(self)
        guard.onWindow(game, active = false)
        guard.begin()

        assertEquals(game, guard.target)
        assertFalse(guard.onWindow(game, active = true))
        assertEquals(game, guard.target)
    }

    /** 从本包（主页/浮层）起播：第一次外部包名锁定为目标，不能误停。 */
    @Test
    fun `本包起播后切到目标应用不停`() {
        val guard = ExternalAppGuard(self)
        guard.onWindow(self, active = false)
        guard.begin()
        assertNull(guard.target)

        assertFalse(guard.onWindow(game, active = true))
        assertEquals(game, guard.target)
    }

    /** 还没收到过任何事件就起播：第一个外部包名作为目标。 */
    @Test
    fun `无历史事件时首个外部包名成为目标`() {
        val guard = ExternalAppGuard(self)
        guard.begin()
        assertNull(guard.target)

        assertFalse(guard.onWindow(chat, active = true))
        assertEquals(chat, guard.target)
    }

    /** 目标锁定后切到别的应用：立即报告该停。 */
    @Test
    fun `切到其他应用要停`() {
        val guard = ExternalAppGuard(self)
        guard.onWindow(game, active = false)
        guard.begin()

        assertTrue(guard.onWindow(chat, active = true))
    }

    /** 起播时在本包、目标尚未锁定时，不同外部包名也只会先锁定，不会停。 */
    @Test
    fun `未锁定时不会因首个外部包名误停`() {
        val guard = ExternalAppGuard(self)
        guard.begin()

        assertFalse(guard.onWindow(chat, active = true))
        assertTrue(guard.onWindow(game, active = true))
    }

    /** 本包事件（自家浮层、设置面板、对话框）一律忽略：不停、不锁定。 */
    @Test
    fun `本包事件忽略`() {
        val guard = ExternalAppGuard(self)
        guard.begin()

        assertFalse(guard.onWindow(self, active = true))
        assertNull(guard.target)
        assertEquals(self, guard.foreground)

        guard.onWindow(game, active = true)
        assertFalse(guard.onWindow(self, active = true))
        assertEquals(game, guard.target)
    }

    /** 暂停中切应用同样要停：按继续后注入会落到新应用上。 */
    @Test
    fun `暂停中切应用也要停`() {
        val guard = ExternalAppGuard(self)
        guard.onWindow(game, active = false)
        guard.begin()

        assertTrue(guard.onWindow(chat, active = true))
    }

    /** 空闲时不判定，但要记录前台：下一次起播用它锁定目标。 */
    @Test
    fun `空闲时只记录前台不判定`() {
        val guard = ExternalAppGuard(self)
        guard.onWindow(game, active = true) // 停留在目标上
        assertFalse(guard.onWindow(chat, active = false))

        guard.begin()
        assertEquals(chat, guard.target)
    }

    /** 前台是本包时起播不沿用旧包名：否则切到真正要跑的应用会被误停。 */
    @Test
    fun `起播前台是本包时不沿用旧包名`() {
        val guard = ExternalAppGuard(self)
        guard.onWindow(game, active = false)
        guard.onWindow(self, active = false)

        guard.begin()
        assertNull(guard.target)
        assertFalse(guard.onWindow(chat, active = true))
        assertEquals(chat, guard.target)
    }

    /** 本轮结束清目标；下一次起播按当时前台重新锁定。 */
    @Test
    fun `结束后目标作废`() {
        val guard = ExternalAppGuard(self)
        guard.onWindow(game, active = false)
        guard.begin()
        guard.end()
        assertNull(guard.target)

        assertFalse(guard.onWindow(game, active = true))
        assertEquals(game, guard.target)
    }

    /** 空包名 / null 忽略，不影响已有目标。 */
    @Test
    fun `空包名忽略`() {
        val guard = ExternalAppGuard(self)
        guard.onWindow(game, active = false)
        guard.begin()

        assertFalse(guard.onWindow(null, active = true))
        assertFalse(guard.onWindow("", active = true))
        assertEquals(game, guard.target)
    }

    // ── 默认输入法：叠在目标应用上方，却报自己的包名 ──────────

    /** 播放中点开输入框，输入法窗口事件不能当成「切到别的应用」。 */
    @Test
    fun `输入法窗口不算应用切换`() {
        val guard = ExternalAppGuard(self, setOf(ime))
        guard.onWindow(game, active = false)
        guard.begin()

        assertFalse(guard.onWindow(ime, active = true))
        assertEquals(game, guard.target)
        // 前台仍记着目标应用：忽略包不该顶掉它
        assertEquals(game, guard.foreground)
    }

    /** 输入法窗口也不能成为本次锁定的目标，否则回到目标应用反而被判成切换。 */
    @Test
    fun `输入法不会成为锁定目标`() {
        val guard = ExternalAppGuard(self, setOf(ime))
        guard.begin()

        assertFalse(guard.onWindow(ime, active = true))
        assertNull(guard.target)

        assertFalse(guard.onWindow(game, active = true))
        assertEquals(game, guard.target)
        assertFalse(guard.onWindow(ime, active = true))
        assertFalse(guard.onWindow(game, active = true))
    }

    /** 起播时前台正是输入法（键盘还开着）：目标留空，等真正的应用出现再锁定。 */
    @Test
    fun `起播时前台是输入法不据此锁定`() {
        val guard = ExternalAppGuard(self, setOf(ime))
        guard.onWindow(ime, active = false)
        guard.begin()

        assertNull(guard.target)
        assertNull(guard.foreground)
    }

    /** 没配置忽略集合时行为不变：输入法仍按外部应用处理（旧行为，供回归对照）。 */
    @Test
    fun `未配置忽略集合时输入法仍算外部应用`() {
        val guard = ExternalAppGuard(self)
        guard.onWindow(game, active = false)
        guard.begin()

        assertTrue(guard.onWindow(ime, active = true))
    }

    /** 系统设置里的输入法标识是 `包名/服务类名`，只取包名。 */
    @Test
    fun `输入法标识解析出包名`() {
        assertEquals(ime, imePackageOf("$ime/.Service"))
        assertEquals(ime, imePackageOf("$ime/com.example.ImeService"))
        assertEquals(ime, imePackageOf(ime))
        assertNull(imePackageOf(null))
        assertNull(imePackageOf(""))
        assertNull(imePackageOf("/.Service"))
    }
}
