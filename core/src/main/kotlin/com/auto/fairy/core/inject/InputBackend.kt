package com.auto.fairy.core.inject

import com.auto.fairy.core.runtime.Slice

/**
 * 注入后端。批式接口：一次调用执行一整片，把 binder/进程往返开销摊到整片上。
 *
 * 实现契约（必须遵守，否则 R10 / 时序验收失效）：
 * 1. [execute] **阻塞到片尾才返回**，绝不提前返回；片内每条 stroke 的绝对时刻为
 *    `slice.absoluteStartNs + stroke.startMs` 纳秒（stroke.startMs 已相对片首）。
 * 2. 片内 stroke 之间不重叠（由编译器保证）；一个 stroke 内的多条 path 表示多指同时按下。
 * 3. 不得缓存 [Slice] 引用：同一 Slice 对象会在循环回放中被反复复用。
 * 4. 返回失败时保留进度由引擎负责：引擎不会越过失败切片继续播放。
 */
interface InputBackend {

    val caps: Caps

    /** 建立通道（例如等待无障碍服务就绪）。 */
    suspend fun prepare(): Result<Unit>

    /** 执行一整片并阻塞到片尾；[Slice] 内坐标是归一化 [0,1]。 */
    suspend fun execute(slice: Slice): Result<Unit>

    fun health(): Health
}
