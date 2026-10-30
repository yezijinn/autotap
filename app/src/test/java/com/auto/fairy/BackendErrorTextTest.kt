package com.auto.fairy

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 后端不可用文案只有一处来源。
 *
 * 曾经 delegate 为 null 写「未连接」、close 后写「已断开」——同一故障两种说法，
 * 用户与日志对照时会以为出了两个不同的问题。现在都引用同一常量。
 */
class BackendErrorTextTest {

    @Test
    fun `后端不可用文案单一来源`() {
        // 常量必须与诊断页「未开启/未连接」的措辞同一套说法
        assertEquals("无障碍服务未连接", A11yBackend.ERR_NOT_CONNECTED)
    }
}
