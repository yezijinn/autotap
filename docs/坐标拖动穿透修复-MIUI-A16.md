# 坐标拖动「手指拖不动 / 触摸穿透」修复（MIUI / HyperOS · Android 16）

> 现象：坐标层十字可见、位置也对，但真机手指按在十字上拖动，**圆点不跟随、松手也不保存**，触摸像是「穿透到下一层」。
> 结论：这是 ROM 对**全透明小尺寸悬浮窗**的触摸路由行为导致的；改成「非全透明像素 + 无障碍覆盖层」后恢复。

## 1. 现象与初判

- 十字（坐标标记）正常显示，位置与脚本点一致。
- 手指拖不动；但用 `adb shell input swipe`（注入触摸）却能拖动。
- 用户描述：「触摸穿透了，传给下一层，没传给坐标层」。

## 2. 排查过程（真机 emulator 之外的 XIAOMI 22127RK46C / Android 16 / HyperOS 3.0.303）

### 2.1 先排除「位置错位」

- 从 `dumpsys window windows` 取热区窗口 `mAttrs=(x,y)(140x140)`，中心与脚本点像素一一对应。
- 再对整屏截图做像素扫描，红色十字的质心也正好落在同一坐标（+1@465,1335；+2@804,1277；+2@499,1803→569,1873；+3@1218,2010）。
- ⇒ **十字与热区完全重合，不是错位问题。**

> 复盘中曾一度误判为「绘制层被状态栏内边距下移」；用全分辨率像素质心核对后确认并无错位。
> （仍保留一处防御：`onDraw` 用 `getLocationOnScreen` 抵消窗口偏移，正常为 0，不影响。）

### 2.2 用日志定位「触摸到底去了哪」

在拖动监听与窗口重建处打临时 `Log.i("MKDRAG", …)`：

- 注入触摸（`input swipe` / 内核 `sendevent`）：日志出现 `DOWN → DRAG start → UP`，脚本点精确改到落点。
- **真实手指触摸**：`MKDRAG` **完全没有 `DOWN`**；同时 `logcat` 里 `MIUIInput ... publisher action=0x0 ... channel 'com.blackberry.keyboard'` 或落到 `MainActivity` / `[Gesture Monitor] miui-gesture`。

⇒ 手指的硬件触摸**没有进入热区窗口的 `onTouch`**，被 ROM 判给了下一层；注入触摸则能正常进入。
⇒ 不是应用逻辑问题，是**输入分发层的窗口命中**。

### 2.3 定位到「全透明悬浮窗」

热区原本是一个 **像素全透明** 的 `TYPE_APPLICATION_OVERLAY` 小窗（`setBackgroundColor(Color.TRANSPARENT)`）。
MIUI/HyperOS 对「像素全透明的悬浮窗」会按**触摸穿透**处理（防透明窗口劫持触摸）：硬件手指事件不投递给它，而是漏给下层。

- 之前已试过把窗口类型改成 `TYPE_ACCESSIBILITY_OVERLAY` —— **单独改类型仍不行**，只要像素全透明就继续穿透。
- 决定性的一步：给热区一个 **1/255 的近乎不可见底色（`0x01000000`）**，使其不再被判定为「全透明」。此后手指触摸正常命中热区，拖动恢复。

## 3. 根因（一句话）

> MIUI/HyperOS 把**像素全透明的悬浮窗**当作触摸穿透窗口；硬件手指事件不会送达它，只有注入事件能进去。所以「能看到十字、但手指拖不动」。

## 4. 修复内容

1. **热区不再全透明**
   - `setBackgroundColor(Color.TRANSPARENT)` → `setBackgroundColor(0x01000000)`（alpha 1/255，人眼不可见）。
   - 这是让 ROM 判定为「可见窗」、愿意投递硬件触摸的关键。

2. **热区用无障碍覆盖层**
   - 通过无障碍服务的 `WindowManager` 挂 `TYPE_ACCESSIBILITY_OVERLAY`（`AutoFairyAccessibilityService.instance`），
     比普通 `TYPE_APPLICATION_OVERLAY` 优先级高、更适合接收硬件触摸；无服务时回退普通悬浮窗。
   - 需要在无障碍服务「已连接」状态下才生效。

3. **窗口标志补全**
   - 热区 `LayoutParams` 增加 `FLAG_NOT_TOUCH_MODAL`（窗内接收、窗外放行）与 `FLAG_LAYOUT_NO_LIMITS`（与绘制层同一坐标空间）。

4. **绘制与热区对齐（防御）**
   - `CoordinateMarkers.onDraw` 用 `getLocationOnScreen` 减去本视图屏幕偏移，十字按**绝对屏幕坐标**绘制，保证与热区严格重合。

5. **（保留）临时诊断日志 `MKDRAG`**
   - `rebuildTouchWindows` 与拖动监听处保留 `Log.i`，便于后续真机排查；正式发版前可删除。

## 5. 验证

- 真机（XIAOMI 22127RK46C / Android 16）手指拖动十字：坐标点精确改到落点，松手保存。
- `MKDRAG` 日志出现 `DOWN → DRAG start → UP`。
- 像素扫描：十字质心 == 热区中心（465,1335 等）。

## 6. 备注 / 边界

- 该修复依赖**无障碍服务已连接**（只是「已开启」不够）；未连接时回退普通悬浮窗，可能重新出现穿透。
- 播放中 / 暂停时会撤掉触摸窗（防止吞掉脚本自身注入的点击），此时不可拖，属设计。
- 若将来在其他 ROM 上遇到同类「全透明窗穿透」，本方案（非全透明像素 + 无障碍覆盖层）可复用。
