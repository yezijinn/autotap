# 精灵点击器（autotap）

免 root 的通用自动点击 / 手势回放工具。脚本可录制即用，也可编辑。点击只用无障碍派发，不使用 root。

## 功能规划

v1.0：无障碍手势回放、循环与延时、悬浮窗播放控制、脚本 CRUD 与导入导出、检查更新。

## 构建

环境要求：JDK 21、Android SDK 34。运行要求：Android 9（API 28）及以上。

```bat
:: 仅编译（release，签名由环境变量注入）
gradlew.bat assembleRelease

:: 一键构建：编译 + 签名校验 + 体积门禁（2.5MB）+ 复制到根目录
python build_apk.py

:: 编译并安装到设备（多设备时用 --device 指定序列号）
python build_apk.py --install
```

签名密钥不随仓库分发，路径与口令由本机环境提供（`JINN_KEYSTORE_ROOT` 等变量）。缺少密钥时脚本会明确报错，不使用任何仓库内密钥。

## 许可

本项目采用 GPL-3.0 协议，详见 [LICENSE](LICENSE)。
