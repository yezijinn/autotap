package com.auto.fairy

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * 浮层根容器：注入期间吞掉触摸。
 *
 * 悬浮窗不是 Activity，没法像 [PanelActivity] 那样覆写 `dispatchTouchEvent`，
 * 而浮层打开只把回放停到片边界（≤400ms）：这段窗口里引擎仍在派发手势，
 * 注入的点击会落到浮层的按钮/行上，用户看不见也拦不住。
 *
 * 实测（第二十六轮）：播放中打开加号层、脚本正点在该层「点击」按钮中心，层里的
 * 「点击」被注入点中一次，脚本就被静默追加 1 步（追加后层自己关闭，所以再打开
 * 才会再被追加——同一个删除/追加入口反复踩到，代价是一次一步，不是一次多步）。
 * 脚本列表同理会命中「激活 / 删除」。
 * 处置与 [PanelActivity] 相同：先重新暂停（覆盖音量键恢复的路径），再丢弃这一串事件。
 */
class InjectShieldLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (shieldModalTouch(ev)) return true
        return super.dispatchTouchEvent(ev)
    }
}
