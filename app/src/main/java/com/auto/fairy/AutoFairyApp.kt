package com.auto.fairy

import android.app.Application
import android.content.Context
import com.auto.fairy.overlay.CoordinateMarkersController

/**
 * 进程级装配点：脚本仓库与回放控制器都在这里初始化一次。
 *
 * 单进程 + 单实例是回放正确性的前提（设计方案 3.2），因此不使用任何 DI 框架，
 * 只保留这两个进程级单例。
 */
class AutoFairyApp : Application() {

    val repository: ScriptRepository by lazy { ScriptRepository(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        PlaybackController.init(this)
        // 迷你条可能被拖到任何位置：坐标层按它的矩形避让，避免触摸窗盖住按钮
        CoordinateMarkersController.registerObstacle("mini_bar") { OverlayController.boundsPx() }
        // 这里不挂坐标层：进程刚起没有会话，闸门必关，调用只会空转；
        // 这行曾是「没激活也有红点」的入口。挂层只发生在 [OverlayController.show]。
    }

    companion object {
        private lateinit var instance: AutoFairyApp

        fun of(context: Context): AutoFairyApp =
            context.applicationContext as AutoFairyApp
    }
}
