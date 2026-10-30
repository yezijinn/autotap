package com.auto.fairy.core.model

/**
 * 归一化坐标，取值 [0,1]，(0,0) 为屏幕左上角。
 *
 * 内核只处理归一化坐标；像素换算由注入后端按实际屏幕尺寸完成（分辨率/旋转变更时
 * 不需要重新编译脚本，见设计方案 R7）。
 */
data class Point(val x: Float, val y: Float)
