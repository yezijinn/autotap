# 精灵点击器 M2 UI 设计规格

> 包名 `com.auto.fairy`，技术栈 **Views + ViewBinding + AppCompat**，不引入 Compose、不引入 Material 组件库。
> 所有圆角/配色/背景均用自绘 **shape drawable / VectorDrawable** 实现，图标走 VectorDrawable 以压体积。
> 本文件面向 `@fixer`：每个布局均为可直接落地的 `res/layout/*.xml` 规格，并含 view id 命名规范。

---

## 0. 资源变更清单（落地前先改这三个文件）

### 0.1 `res/values/colors.xml` 追加（不覆盖既有 `brand_*`）

```xml
    <!-- 状态色：运行中 / 暂停 / 停止 / 错误 -->
    <color name="status_running">#2BB673</color>
    <color name="status_paused">#F2994A</color>
    <color name="status_stopped">#9AA0A6</color>
    <color name="status_error">#EB5757</color>

    <!-- 表层 -->
    <color name="surface">#FFFFFFFF</color>
    <color name="surface_variant">#F2F4F7</color>
    <color name="text_primary">#1A1D21</color>
    <color name="text_secondary">#6B7280</color>
    <color name="divider">#E5E7EB</color>

    <!-- 悬浮窗背景（深色半透明，保证叠在任何 App 上都可读） -->
    <color name="overlay_panel">#1F2937</color>
    <color name="overlay_panel_alpha">#E61F2937</color>
    <color name="overlay_text">#FFFFFFFF</color>
    <color name="overlay_text_secondary">#C7CBD1</color>

    <!-- 录制浮层遮罩 -->
    <color name="record_scrim">#99000000</color>
```

### 0.2 `res/values/strings.xml` 追加

```xml
    <!-- 主页面 -->
    <string name="nav_title_scripts">脚本</string>
    <string name="nav_title_diagnose">诊断</string>
    <string name="nav_title_keepalive">保活</string>
    <string name="nav_title_settings">设置</string>
    <string name="empty_scripts_title">还没有脚本</string>
    <string name="empty_scripts_hint">点击下方按钮新建，或导入一个脚本文件</string>
    <string name="fab_new_script">新建脚本</string>
    <string name="fab_import_script">导入脚本</string>
    <string name="script_loops_format">循环 %1$d 次</string>
    <string name="script_loops_infinite">无限循环</string>
    <string name="script_steps_format">%1$d 步</string>
    <string name="script_modified_format">修改于 %1$s</string>
    <string name="status_playing">播放中</string>
    <string name="status_paused_label">已暂停</string>
    <string name="status_error_label">出错</string>
    <string name="menu_play">播放</string>
    <string name="menu_edit">编辑</string>
    <string name="menu_export">导出</string>
    <string name="menu_delete">删除</string>
    <string name="toast_permission_overlay">请授予「显示在其他应用上层」权限</string>
    <string name="toast_permission_a11y">请在无障碍设置中开启精灵点击器</string>

    <!-- 悬浮窗 -->
    <string name="overlay_collapsed_desc">展开播放控制</string>
    <string name="overlay_expanded_desc">收起播放控制</string>
    <string name="overlay_loop_format">循环 %1$s</string>
    <string name="overlay_loop_infinite">∞</string>
    <string name="overlay_open_list">打开列表</string>
    <string name="overlay_health_alive">正常</string>
    <string name="overlay_health_dead">已失效</string>

    <!-- 录制浮层 -->
    <string name="record_title">正在录制</string>
    <string name="record_steps_format">已录 %1$d 步</string>
    <string name="record_duration_format">%1$s</string>
    <string name="record_hint">录制期间无法操作底层应用，完成或取消后退出</string>
    <string name="record_cancel">取消</string>
    <string name="record_done">完成</string>

    <!-- 占位页（M3+） -->
    <string name="placeholder_diagnose">诊断页（M3 实现）</string>
    <string name="placeholder_keepalive">保活引导页（M3 实现）</string>
    <string name="placeholder_settings">设置页（M3 实现）</string>
```

### 0.3 `res/values/themes.xml` 微调（仅补状态栏/控件色，保持 DayNight.NoActionBar）

```xml
    <style name="Theme.AutoFairy" parent="Theme.AppCompat.DayNight.NoActionBar">
        <item name="colorPrimary">@color/brand_primary</item>
        <item name="colorPrimaryDark">@color/brand_primary_dark</item>
        <item name="colorAccent">@color/brand_primary</item>
        <item name="android:windowBackground">@color/window_background</item>
        <!-- 去掉默认按钮蓝边/涟漪造成的过度绘制感 -->
        <item name="android:buttonStyle">@style/Widget.AutoFairy.Button</item>
        <item name="android:imageButtonStyle">@style/Widget.AutoFairy.ImageButton</item>
    </style>

    <style name="Widget.AutoFairy.Button" parent="Widget.AppCompat.Button">
        <item name="android:background">@null</item>
    </style>

    <style name="Widget.AutoFairy.ImageButton" parent="Widget.AppCompat.ImageButton">
        <item name="android:background">@null</item>
    </style>
```

---

## 1. 信息架构（M2）

```
精灵点击器 (单 Activity，多 Fragment 容器)
├─ 主页面 MainActivity + 内容容器
│   ├─ ScriptListFragment  (M2 核心：脚本列表 + 状态 + FAB + 顶部栏)
│   ├─ DiagnoseFragment     (M2 占位入口，仅标题 + 提示)
│   ├─ KeepAliveFragment    (M2 占位入口，仅标题 + 提示)
│   └─ SettingsFragment     (M2 占位入口，仅标题 + 提示)
├─ OverlayController (M2 核心：WindowManager 悬浮窗，跨页面常驻)
└─ RecordOverlay    (M2 核心：全屏透明录制浮层)
```

**M2 范围决策**
- 必做：主页面（脚本列表 + 状态 + FAB + 顶部栏）、悬浮窗、录制浮层。
- 先做占位：诊断 / 保活 / 设置 三个页仅给入口 + 占位 Fragment（标题 + 一行说明），布局复用 `fragment_placeholder.xml`。
- 录制入口：主页面 FAB「新建」旁提供「录制」快捷方式（见 §2.5）。

---

## 2. 主页面（脚本列表）

### 2.1 设计令牌（统一规范，全 App 复用）

| 类别 | 值 | 说明 |
|---|---|---|
| 间距网格 | 8dp | 所有外边距/内边距为 8 的倍数（8/16/24） |
| 圆角 | 12dp 卡片 / 16dp 面板 / 28dp FAB | 统一 `res/values/dimens.xml` 的 `corner_*` |
| 主色 | `#2F6FED` | 既有的 `brand_primary` |
| 状态色 | 绿 `#2BB673` / 橙 `#F2994A` / 灰 `#9AA0A6` / 红 `#EB5757` | 见 §0.1 |
| 字号 | 标题 20sp / 卡片名 16sp / 正文 14sp / 辅助 12sp | 见 §0.2 文案 |
| 点击靶面 | ≥ 44dp | ImageButton/菜单项最小 44×44 |

### 2.2 自绘背景 drawable（放 `res/drawable/`）

`bg_card.xml`（白色圆角卡片 + 细描边，避免阴影过度绘制）：
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="@color/surface" />
    <stroke android:width="1dp" android:color="@color/divider" />
    <corners android:radius="12dp" />
</shape>
```

`bg_card_active.xml`（播放中高亮：主色描边 + 极淡主色底）：
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#0D2F6FED" />
    <stroke android:width="1.5dp" android:color="@color/brand_primary" />
    <corners android:radius="12dp" />
</shape>
```

`bg_fab.xml`（主色圆角 FAB）：
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="@color/brand_primary" />
    <corners android:radius="28dp" />
</shape>
```

`bg_chip.xml`（循环次数小药丸，浅灰底深字）：
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="@color/surface_variant" />
    <corners android:radius="8dp" />
</shape>
```

### 2.3 `activity_main.xml`（M2 重写为「内容容器 + 顶部栏 + FAB」）

> 旧的占位 `activity_main.xml` 整文件替换为此。顶部用自绘 `LinearLayout` 代替 ActionBar（主题已 NoActionBar）。

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/root_main"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/window_background">

    <!-- 顶部应用栏：标题 + 诊断 + 设置（自绘，无 Material） -->
    <LinearLayout
        android:id="@+id/appbar"
        android:layout_width="match_parent"
        android:layout_height="56dp"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:paddingStart="16dp"
        android:paddingEnd="8dp"
        android:background="@color/surface">

        <TextView
            android:id="@+id/text_appbar_title"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="@string/app_name"
            android:textSize="20sp"
            android:textStyle="bold"
            android:textColor="@color/text_primary" />

        <ImageButton
            android:id="@+id/btn_diagnose"
            android:layout_width="44dp"
            android:layout_height="44dp"
            android:contentDescription="@string/nav_title_diagnose"
            android:src="@drawable/ic_diagnose"
            android:scaleType="centerInside"
            android:padding="10dp" />

        <ImageButton
            android:id="@+id/btn_settings"
            android:layout_width="44dp"
            android:layout_height="44dp"
            android:contentDescription="@string/nav_title_settings"
            android:src="@drawable/ic_settings"
            android:scaleType="centerInside"
            android:padding="10dp" />
    </LinearLayout>

    <!-- 内容容器：Fragment 在此切换（ScriptList / Placeholder） -->
    <FrameLayout
        android:id="@+id/container"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:layout_marginTop="56dp" />

    <!-- 悬浮新建/导入/录制 FAB（主色圆角） -->
    <LinearLayout
        android:id="@+id/fab_group"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:layout_gravity="bottom|end"
        android:layout_marginEnd="16dp"
        android:layout_marginBottom="16dp"
        android:gravity="center_vertical">

        <TextView
            android:id="@+id/fab_label"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/fab_new_script"
            android:textSize="14sp"
            android:textColor="@color/overlay_text"
            android:background="@drawable/bg_overlay_panel_small"
            android:paddingStart="12dp"
            android:paddingEnd="12dp"
            android:paddingTop="8dp"
            android:paddingBottom="8dp"
            android:layout_marginEnd="8dp" />

        <ImageButton
            android:id="@+id/fab_new_script"
            android:layout_width="56dp"
            android:layout_height="56dp"
            android:contentDescription="@string/fab_new_script"
            android:src="@drawable/ic_add"
            android:background="@drawable/bg_fab"
            android:tint="@color/overlay_text"
            android:scaleType="centerInside"
            android:padding="16dp" />
    </LinearLayout>

</FrameLayout>
```

> 说明：FAB 采用「标签 + 圆钮」合并布局，点击圆钮弹出一个轻量菜单（新建/导入/录制）由 `activity` 用 `PopupWindow` 或自定义 `LinearLayout` 实现，避免引入 Material 的 `FloatingActionButton` / `BottomAppBar`。M2 可先只绑定「新建脚本」与「录制」两个动作。

### 2.4 `fragment_script_list.xml`（列表容器 + 空态）

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <!-- 列表 -->
    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/recycler_scripts"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:padding="16dp"
        android:clipToPadding="false"
        android:scrollbars="vertical" />

    <!-- 空态（默认 GONE，列表为空时显示） -->
    <LinearLayout
        android:id="@+id/empty_scripts"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:gravity="center"
        android:layout_gravity="center"
        android:visibility="gone">

        <ImageView
            android:layout_width="72dp"
            android:layout_height="72dp"
            android:src="@drawable/ic_empty"
            android:tint="@color/text_secondary"
            android:alpha="0.5" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:text="@string/empty_scripts_title"
            android:textSize="16sp"
            android:textColor="@color/text_primary" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="4dp"
            android:text="@string/empty_scripts_hint"
            android:textSize="13sp"
            android:textColor="@color/text_secondary" />
    </LinearLayout>

</FrameLayout>
```

### 2.5 `item_script_card.xml`（列表项卡片）

> 用 `FrameLayout` 包背景 + 内容，便于整卡切换 `bg_card` / `bg_card_active`。状态色小圆点 `img_status` 仅在播放中/出错时可见。

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/card_script"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginBottom="12dp"
    android:background="@drawable/bg_card"
    android:padding="16dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical">

        <!-- 状态色圆点（默认隐藏，播放/出错时显示） -->
        <View
            android:id="@+id/img_status"
            android:layout_width="10dp"
            android:layout_height="10dp"
            android:layout_marginEnd="12dp"
            android:background="@drawable/bg_status_dot"
            android:visibility="gone" />

        <LinearLayout
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:orientation="vertical">

            <TextView
                android:id="@+id/text_script_name"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:textSize="16sp"
                android:textStyle="bold"
                android:textColor="@color/text_primary"
                android:maxLines="1"
                android:ellipsize="end"
                tools:text="每日签到" />

            <!-- 循环 / 步数 / 状态（一行） -->
            <LinearLayout
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:layout_marginTop="6dp"
                android:gravity="center_vertical">

                <TextView
                    android:id="@+id/text_script_loops"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:textSize="12sp"
                    android:textColor="@color/text_secondary"
                    android:background="@drawable/bg_chip"
                    android:paddingStart="8dp"
                    android:paddingEnd="8dp"
                    android:paddingTop="3dp"
                    android:paddingBottom="3dp" />

                <TextView
                    android:id="@+id/text_script_steps"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginStart="8dp"
                    android:textSize="12sp"
                    android:textColor="@color/text_secondary" />

                <TextView
                    android:id="@+id/text_script_state"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginStart="8dp"
                    android:textSize="12sp"
                    android:textStyle="bold"
                    android:visibility="gone" />
            </LinearLayout>

            <TextView
                android:id="@+id/text_script_modified"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="6dp"
                android:textSize="12sp"
                android:textColor="@color/text_secondary" />
        </LinearLayout>

        <!-- 卡片菜单（播放/编辑/导出/删除） -->
        <ImageButton
            android:id="@+id/btn_script_menu"
            android:layout_width="44dp"
            android:layout_height="44dp"
            android:contentDescription="@string/nav_title_settings"
            android:src="@drawable/ic_more"
            android:scaleType="centerInside"
            android:padding="10dp" />
    </LinearLayout>

</FrameLayout>
```

> `text_script_state` 文本由代码设置：`status_playing`（绿）/ `status_paused_label`（橙）/ `status_error_label`（红），颜色同步 `textColor`。`img_status` 背景 `bg_status_dot.xml` 为 5dp 圆、颜色由代码设（绿/橙/红）。

### 2.6 `fragment_placeholder.xml`（诊断/保活/设置占位，M3 替换）

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:gravity="center"
    android:padding="24dp">

    <TextView
        android:id="@+id/text_placeholder_title"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:textSize="20sp"
        android:textStyle="bold"
        android:textColor="@color/text_primary" />

    <TextView
        android:id="@+id/text_placeholder_hint"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:textSize="14sp"
        android:textColor="@color/text_secondary" />
</LinearLayout>
```

---

## 3. 悬浮窗（OverlayController）

### 3.1 行为约束
- 常驻 `WindowManager`，`TYPE_APPLICATION_OVERLAY`，跨页面显示，不挡屏。
- **折叠态**：小圆钮（状态色 + 可选脉冲动画），常驻屏幕边缘。
- **展开态**：面板含 播放/暂停、停止、循环次数、打开列表 四个一触即达控件。
- **拖动 vs 点击**：`MotionEvent` 中记录 `downX/downY` 与 `downTime`；移动超过 **8dp** 视为拖动（不触发展开），否则视为点击（切换展开/收起）。阈值常量由 fixer 抽成 `DRAG_SLOP = 8dp`。
- **边缘吸附（可选 M2）**：松手时若靠近左右边缘，动画吸附到边缘并保留 8dp 内边距；默认开启，可在设置页关。

### 3.2 `overlay_window.xml`（折叠 + 展开同布局，靠 `visibility` 切换）

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/overlay_root"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content">

    <!-- 折叠态：状态色圆钮 -->
    <FrameLayout
        android:id="@+id/overlay_collapsed"
        android:layout_width="48dp"
        android:layout_height="48dp"
        android:background="@drawable/bg_overlay_collapsed">

        <View
            android:id="@+id/overlay_status_dot"
            android:layout_width="14dp"
            android:layout_height="14dp"
            android:layout_gravity="center"
            android:background="@drawable/bg_status_dot_big" />
    </FrameLayout>

    <!-- 展开态：控制面板 -->
    <LinearLayout
        android:id="@+id/overlay_expanded"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:background="@drawable/bg_overlay_panel"
        android:padding="12dp"
        android:visibility="gone">

        <!-- 标题行：状态文字 + 健康角标 -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:gravity="center_vertical"
            android:paddingBottom="8dp">

            <TextView
                android:id="@+id/text_overlay_state"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:textSize="14sp"
                android:textStyle="bold"
                android:textColor="@color/overlay_text" />

            <TextView
                android:id="@+id/text_overlay_health"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:textSize="11sp"
                android:textColor="@color/overlay_text_secondary"
                android:background="@drawable/bg_chip_dark"
                android:paddingStart="6dp"
                android:paddingEnd="6dp"
                android:paddingTop="2dp"
                android:paddingBottom="2dp" />
        </LinearLayout>

        <!-- 主控制行：播放/暂停、停止 -->
        <LinearLayout
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:gravity="center_vertical">

            <ImageButton
                android:id="@+id/btn_overlay_play"
                android:layout_width="48dp"
                android:layout_height="48dp"
                android:contentDescription="@string/menu_play"
                android:src="@drawable/ic_play"
                android:tint="@color/overlay_text"
                android:scaleType="centerInside"
                android:padding="12dp" />

            <ImageButton
                android:id="@+id/btn_overlay_pause"
                android:layout_width="48dp"
                android:layout_height="48dp"
                android:layout_marginStart="4dp"
                android:contentDescription="@string/menu_play"
                android:src="@drawable/ic_pause"
                android:tint="@color/overlay_text"
                android:scaleType="centerInside"
                android:padding="12dp"
                android:visibility="gone" />

            <ImageButton
                android:id="@+id/btn_overlay_stop"
                android:layout_width="48dp"
                android:layout_height="48dp"
                android:layout_marginStart="4dp"
                android:contentDescription="@string/menu_delete"
                android:src="@drawable/ic_stop"
                android:tint="@color/status_error"
                android:scaleType="centerInside"
                android:padding="12dp" />
        </LinearLayout>

        <!-- 信息行：循环次数 + 打开列表 -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:gravity="center_vertical"
            android:layout_marginTop="8dp">

            <TextView
                android:id="@+id/text_loop_count"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:textSize="12sp"
                android:textColor="@color/overlay_text_secondary" />

            <TextView
                android:id="@+id/btn_overlay_open_list"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/overlay_open_list"
                android:textSize="12sp"
                android:textColor="@color/brand_primary"
                android:background="@drawable/bg_chip_dark"
                android:paddingStart="10dp"
                android:paddingEnd="10dp"
                android:paddingTop="6dp"
                android:paddingBottom="6dp" />
        </LinearLayout>
    </LinearLayout>

</FrameLayout>
```

### 3.3 悬浮窗专属 drawable（`res/drawable/`）

`bg_overlay_collapsed.xml`（状态色圆，默认绿，代码按 Health/State 改 `backgroundTint`）：
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="oval">
    <solid android:color="@color/status_running" />
</shape>
```

`bg_overlay_panel.xml`（深色半透明圆角面板）：
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="@color/overlay_panel_alpha" />
    <corners android:radius="16dp" />
</shape>
```

`bg_overlay_panel_small.xml`（FAB 标签用，同色小圆角）：
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="@color/overlay_panel_alpha" />
    <corners android:radius="14dp" />
</shape>
```

`bg_chip_dark.xml`（深色面板上的浅字药丸）：
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#33FFFFFF" />
    <corners android:radius="8dp" />
</shape>
```

`bg_status_dot.xml` / `bg_status_dot_big.xml`（状态小圆点）：
```xml
<!-- bg_status_dot.xml -->
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
    <solid android:color="@color/status_running" />
</shape>
<!-- bg_status_dot_big.xml 同形，仅尺寸由 layout 控制，颜色由代码设 -->
```

### 3.4 状态/健康 → 颜色映射（fixer 实现参考）

| 播放状态 | 折叠钮色 | 展开 `text_overlay_state` |
|---|---|---|
| 运行 | 绿 `status_running` | 「播放中」 |
| 暂停 | 橙 `status_paused` | 「已暂停」 |
| 停止/空闲 | 灰 `status_stopped` | 「就绪」 |
| 错误 | 红 `status_error` | 「出错」 |

**健康角标（`text_overlay_health`，叠加在状态色之上）**
- `Health.ALIVE` → 不显示角标（或显示「正常」灰字）
- `Health.DEAD` → 角标「已失效」红字，折叠钮变红，且 `btn_overlay_play` 禁用

---

## 4. 录制浮层（RecordOverlay）

### 4.1 行为约束
- `<API 34`：全屏 `TYPE_APPLICATION_OVERLAY` 透明窗口，拦截触摸事件（不向下透传），自己记录坐标后交给录制内核。
- `>=API 34`：优先用系统 `ACTION_CREATE_NOTE` / `MediaProjection` 录制手势（M2 可先统一走透明浮层，系统级方案标记 TODO）。
- 顶部常驻信息条：已录步数 + 时长 + 说明；底部两个按钮：取消 / 完成。
- 录制期间底部应用不可操作，浮层吃掉触摸（除自身按钮）。

### 4.2 `overlay_record.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/record_root"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <!-- 透明触摸捕获层：覆盖全屏，吃掉底层触摸 -->
    <View
        android:id="@+id/record_touch_layer"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:background="@android:color/transparent" />

    <!-- 顶部信息条（半透明遮罩） -->
    <LinearLayout
        android:id="@+id/record_banner"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:background="@color/record_scrim"
        android:paddingStart="16dp"
        android:paddingEnd="16dp"
        android:paddingTop="14dp"
        android:paddingBottom="14dp">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:gravity="center_vertical">

            <View
                android:layout_width="10dp"
                android:layout_height="10dp"
                android:background="@drawable/bg_status_dot"
                android:layout_marginEnd="8dp" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/record_title"
                android:textSize="16sp"
                android:textStyle="bold"
                android:textColor="@color/overlay_text" />

            <TextView
                android:id="@+id/text_record_steps"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:layout_marginStart="12dp"
                android:textSize="13sp"
                android:textColor="@color/overlay_text_secondary" />

            <TextView
                android:id="@+id/text_record_duration"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:textSize="13sp"
                android:textColor="@color/overlay_text"
                android:textStyle="bold" />
        </LinearLayout>

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="6dp"
            android:text="@string/record_hint"
            android:textSize="12sp"
            android:textColor="@color/overlay_text_secondary" />
    </LinearLayout>

    <!-- 底部操作条 -->
    <LinearLayout
        android:id="@+id/record_actions"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:layout_gravity="bottom"
        android:gravity="center"
        android:background="@color/record_scrim"
        android:padding="16dp">

        <TextView
            android:id="@+id/btn_record_cancel"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:gravity="center"
            android:text="@string/record_cancel"
            android:textSize="15sp"
            android:textColor="@color/overlay_text"
            android:background="@drawable/bg_chip_dark"
            android:paddingTop="12dp"
            android:paddingBottom="12dp" />

        <TextView
            android:id="@+id/btn_record_done"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:layout_marginStart="12dp"
            android:gravity="center"
            android:text="@string/record_done"
            android:textSize="15sp"
            android:textStyle="bold"
            android:textColor="@color/overlay_text"
            android:background="@drawable/bg_fab"
            android:paddingTop="12dp"
            android:paddingBottom="12dp" />
    </LinearLayout>

</FrameLayout>
```

---

## 5. 配色规范（浅色，v1）

| 用途 | 色值 | 资源名 |
|---|---|---|
| 主色 | `#2F6FED` | `brand_primary` |
| 主色深（状态栏） | `#1F4FB5` | `brand_primary_dark` |
| 运行中 | `#2BB673` | `status_running` |
| 暂停 | `#F2994A` | `status_paused` |
| 停止/空闲 | `#9AA0A6` | `status_stopped` |
| 错误 | `#EB5757` | `status_error` |
| 页面底 | `#FFFFFFFF` | `window_background` |
| 卡片面 | `#FFFFFFFF` | `surface` |
| 浅灰底 | `#F2F4F7` | `surface_variant` |
| 主文字 | `#1A1D21` | `text_primary` |
| 次文字 | `#6B7280` | `text_secondary` |
| 分割线 | `#E5E7EB` | `divider` |
| 悬浮窗面板 | `#1F2937` @ 90% | `overlay_panel_alpha` |
| 录制遮罩 | 黑 @ 60% | `record_scrim` |

**间距（8dp 网格）**：卡片内边距 16、卡片间距 12、列表边距 16、顶部栏高 56、FAB 56、控件靶面 ≥44。
**圆角**：卡片 12 / 面板 16 / FAB 28 / 小药丸 8。
**字号**：标题 20 / 卡片名 16 / 正文 14 / 辅助 12 / 状态 14（粗）。

---

## 6. 图标清单（VectorDrawable）

全部 `res/drawable/ic_*.xml`，`viewportWidth/Height=24`，`fillColor` 默认 `currentColor` 或按使用处 `tint`。

| 图标 | 用途 | 简述路径 |
|---|---|---|
| `ic_play` | 播放/继续 | 三角形 `M8,5 L19,12 L8,19 Z` |
| `ic_pause` | 暂停 | 两竖条 `M6,5 H10 V19 H6 Z` + `M14,5 H18 V19 H14 Z` |
| `ic_stop` | 停止 | 圆角方块 `M6,6 H18 V18 H6 Z`（圆角 2） |
| `ic_record` | 录制入口 | 实心圆 `M12,12 m-6,0 a6,6 0 1,0 12,0 a6,6 0 1,0 -12,0` |
| `ic_add` | 新建/导入 | 加号 `M12,5 V19 M5,12 H19` |
| `ic_more` | 卡片菜单 | 三点竖排 `M12,5 a1.6,1.6 0 1,0 0,0.1` ×3 |
| `ic_diagnose` | 诊断入口 | 放大镜+心跳 `M10,4 a5,5 0 1,0 3.5,8.5` 叠加折线 |
| `ic_settings` | 设置入口 | 齿轮外环 + 中心圆 |
| `ic_empty` | 空态 | 文件夹/列表线框 |

> 图标具体 pathData 由 fixer 按上述几何落盘（均为单色、可 tint，符合体积预算）。

---

## 7. 交互细节

### 7.1 播放中按 Home / 回列表
- 悬浮窗 `TYPE_APPLICATION_OVERLAY` 始终在最上层，按 Home 后回到桌面仍可见、可控制。
- 主页面 `ScriptListFragment` 在 `onResume` 时向 `OverlayController` 拉取当前播放态，刷新对应卡片高亮 + `text_script_state`（绿「播放中」/橙「已暂停」）。
- 从悬浮窗「打开列表」(`btn_overlay_open_list`) → 启动/前台 `MainActivity` 并 `scrollTo` 当前播放卡片。
- 播放中卡片整卡用 `bg_card_active`，`img_status` 显示绿/橙点，`text_script_state` 置可见。

### 7.2 后端失效时悬浮窗角标变化（BackendSupervisor）
- `Health` 由 `InputBackend.health()` 周期性回报，`OverlayController` 订阅后更新 `text_overlay_health` 与折叠钮颜色：
  - **ALIVE**：角标隐藏，折叠钮绿。
  - **DEAD**：角标红字「已失效」，折叠钮变红，`btn_overlay_play` `isEnabled=false`（置灰），点「打开列表」跳诊断页。

### 7.3 拖动 vs 点击（阈值）
- `OverlayController` 在 `overlay_collapsed` 上监听 `MotionEvent`：
  - `ACTION_DOWN` 记录 `startX/startY/startTime`。
  - `ACTION_MOVE` 若 `|dx|+|dy| > DRAG_SLOP(8dp)` → 进入拖动模式，`WindowManager.updateViewLayout` 跟随手指。
  - `ACTION_UP`：拖动模式 → 吸附边缘；非拖动 → 切换 `overlay_collapsed/overlay_expanded` 的 `visibility`（展开态也支持拖整体移动）。
- 展开态面板本身不拦截外部拖动，拖动仍由 `overlay_root` 的 `OnTouchListener` 统一处理。

### 7.4 录制浮层触摸
- `record_touch_layer` 设 `onClick`/`onTouch` 消费事件（`return true`）捕获坐标，阻止透传到底层 App。
- 顶部 `record_banner` 与底部 `record_actions` 需 `android:clickable="true"` 且置于触摸层之上（FrameLayout 后添加即在上），按钮单独处理点击。
- 取消：丢弃已录步骤，关闭浮层；完成：停止捕获、调用 `ScriptCompiler` 生成 `Script` 并存入 `files/scripts/<id>.json`，回主列表刷新。

---

## 8. 落地检查清单（fixer 用）

- [ ] `colors.xml` / `strings.xml` / `themes.xml` 按 §0 追加
- [ ] `res/drawable/` 新增 11 个 shape + 9 个 vector 图标
- [ ] `activity_main.xml` 整文件替换为 §2.3
- [ ] 新增 `fragment_script_list.xml` / `item_script_card.xml` / `fragment_placeholder.xml`
- [ ] 新增 `overlay_window.xml` / `overlay_record.xml`
- [ ] `ScriptListFragment` 用 `RecyclerView` + `item_script_card`，空态切 `empty_scripts`
- [ ] `OverlayController` 用 `WindowManager` 加载 `overlay_window`，实现 §7.3 拖动/点击与 §7.2 健康角标
- [ ] `RecordOverlay` 用 `WindowManager` 加载 `overlay_record`，实现 §7.4 触摸捕获
- [ ] 所有 ID 遵循 `btn_`/`text_`/`img_`/`card_`/`overlay_` 前缀规范
```
