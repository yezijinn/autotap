package com.auto.fairy

import android.content.Context
import android.widget.Toast

/**
 * 吐司的统一出口：短时长、用 application context。
 *
 * 以前每个界面各自写一个 `private fun toast(resId)`（Activity / Fragment /
 * 悬浮窗共七份），实现一模一样，只有 context 取法不同。集中到这里，
 * 新增界面直接调用，不再各写一遍。
 *
 * 名字不带 `toast`：类内的成员函数会遮蔽同名顶层函数（Kotlin 规则），
 * 委托时写成 `toast(...)` 会解析回成员自己，造成无限递归。
 */
fun showToast(context: Context?, resId: Int) {
    context?.let { Toast.makeText(it.applicationContext, resId, Toast.LENGTH_SHORT).show() }
}

/** 文本版：提示里要拼动态内容（脚本名、失败原因）时用。 */
fun showToast(context: Context?, text: String) {
    context?.let { Toast.makeText(it.applicationContext, text, Toast.LENGTH_SHORT).show() }
}
