package com.auto.fairy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无障碍开关的第三判据：[enabledServiceListed]。
 *
 * 钉住的是「系统启用列表漏报本服务，但设置里确实写着它」这一真机故障
 * （MuMu/API 32：服务崩过几次后列表会摘掉它，开关却还是开的）。横幅据此
 * 不再谎报「无障碍服务未开启」。
 */
class EnabledServiceListedTest {

    private val pkg = "com.auto.fairy"
    private val cls = "com.auto.fairy.AutoFairyAccessibilityService"

    @Test
    fun `本服务在串里`() {
        assertTrue(enabledServiceListed("$pkg/$cls", pkg, cls))
    }

    @Test
    fun `多条服务共存时仍认得出本服务`() {
        val other = "com.other.app/com.other.app.Svc"
        assertTrue(enabledServiceListed("$other:$pkg/$cls", pkg, cls))
        assertTrue(enabledServiceListed("$pkg/$cls:$other", pkg, cls))
    }

    @Test
    fun `同包不同类不算`() {
        assertFalse(enabledServiceListed("$pkg/com.auto.fairy.Other", pkg, cls))
    }

    @Test
    fun `另一个包的同名服务不算`() {
        // 调试包与被测包曾经同时装着，类名完全一样，只差包名
        assertFalse(
            enabledServiceListed("com.auto.fairy.p24/$cls", pkg, cls),
        )
        assertFalse(
            enabledServiceListed("com.auto.fairy.p24/.AutoFairyAccessibilityService", pkg, cls),
        )
    }

    @Test
    fun `短形式也认`() {
        // 实测：`settings put secure enabled_accessibility_services
        // com.auto.fairy/.AutoFairyAccessibilityService` 系统照样把服务绑起来，
        // 只认全类名会把这种落盘值判成「没开」
        assertTrue(enabledServiceListed("$pkg/.AutoFairyAccessibilityService", pkg, cls))
        assertTrue(
            enabledServiceListed(
                "com.other.app/com.other.app.Svc:$pkg/.AutoFairyAccessibilityService",
                pkg,
                cls,
            ),
        )
    }

    @Test
    fun `空串与空值都不算`() {
        assertFalse(enabledServiceListed(null, pkg, cls))
        assertFalse(enabledServiceListed("", pkg, cls))
        assertFalse(enabledServiceListed(":", pkg, cls))
    }
}
