# 竖屏守护

<p align="center">
  <a href="https://github.com/tiger-git2022/portrait-guard/releases/latest"><img src="https://img.shields.io/github/v/release/tiger-git2022/portrait-guard?style=for-the-badge&label=%E4%B8%8B%E8%BD%BD%E6%9C%80%E6%96%B0%E7%89%88%20%2F%20Download&color=2ea44f" alt="下载最新版 / Download latest"></a>
</p>

**[⬇ 下载最新 APK / Download the latest APK](https://github.com/tiger-git2022/portrait-guard/releases/latest)**：打开后在 Assets 里点 `.apk` 文件。/ Open it and tap the `.apk` file under Assets.

[中文](#中文) | [English](#english)

## 中文

自用安卓工具：让选定的前台应用临时强制竖屏。

### 功能

- 可以添加多个可启动的应用，分别配置。
- 每条规则有独立的开关，以及解锁后是否显示悬浮按钮的设置。
- 进入已启用的应用时，创建一个透明、不可触摸的无障碍覆盖层来请求竖屏，并显示一个可拖动的悬浮按钮。
- 系统开着自动旋转时，守护期间还会把系统显示方向写成竖屏，适配那些在当前显示方向改变之前会忽略覆盖层请求的厂商系统。系统本来就锁定竖屏时不会改动这项设置。
- 点悬浮按钮会移除竖屏覆盖层，把系统旋转设置恢复成守护前的样子；守护期间你自己改过的旋转设置会保留。
- 回到系统桌面也会移除竖屏覆盖层和悬浮按钮。
- 其他应用在被守护应用上方打开的小窗、通知弹窗或半透明页面不算离开，只要被守护应用仍然可见（Android 10+，需要“使用情况访问权限”）。
- 从后台回到被守护应用时，等它转回横屏后悬浮按钮会重新出现。
- 停用或删除正在生效的规则，会立即移除覆盖层。

### 发布与更新

每次推送 `main`（或手动运行 **Build & Release** 工作流）都会编译签名的 release APK，并发布为 GitHub Release，tag 为 `v1.0.<构建号>`。构建号同时是 `versionCode`，更新说明列出自上一个版本以来的提交。PR 只做 debug 签名的检查构建，拿不到签名 secrets。

release 签名从环境变量读取 `KEYSTORE_FILE`、`KEYSTORE_PASSWORD`、`KEY_ALIAS` 和 `KEY_PASSWORD`；CI 把 `KEYSTORE_BASE64` secret 解码成 `KEYSTORE_FILE`。没有这些变量时，本地 release 构建使用 debug 签名，`versionCode` 为 1。

App 启动时每天最多静默检查一次最新的 GitHub Release（读取发布附件 `update.json`，不受 GitHub API 每小时 60 次的限制），也可以在“应用更新”卡片里手动检查。更新会下载 APK 并交给系统安装器，需要给竖屏守护开启“安装未知应用”权限。签名不同的 APK（例如本地 debug 构建）不能直接覆盖安装，需要先卸载一次再安装 release 版本。

### 安装与测试

1. 用 Android Studio 编译安装 debug APK，或在仓库目录运行 `gradlew.bat :app:assembleDebug`。
2. 打开 App，授予无障碍、悬浮窗（显示在其他应用上层）、修改系统设置、忽略电池优化权限。
3. 添加目标游戏，设置时长，然后打开它的开关。
4. 打开目标游戏，确认它变成竖屏且可以正常触摸，离开游戏或计时结束后恢复原来的旋转设置。

最终效果取决于 vivo Android 16 是否接受无障碍覆盖层对特定游戏的方向请求。如果某个游戏或系统版本拒绝，需要另做 Shizuku/Root 实现。

在 vivo 上，还需要在手机的电池设置里允许竖屏守护后台运行。App 无法绕过你没有批准的系统策略。

Gradle 输出故意放在 `%USERPROFILE%\.gradle\project-builds\portrait-guard`，而不是 OneDrive 文件夹里，避免 OneDrive 在构建时锁住生成的资源文件。

打开“从最近任务中隐藏”开关时，会用 `finishAndRemoveTask()` 把当前设置页从最近任务里移除，不会停止无障碍服务。打开系统权限页面不会调用这个方法，所以那些页面仍可正常使用。

启动器图标使用 `app/src/main/res/drawable-nodpi/rotation_icon.png`。

## English

Self-use Android tool for temporarily forcing selected foreground apps into portrait orientation.

### Behavior

- Configure multiple launchable apps independently.
- Each rule has its own enable switch and post-unlock floating-button setting.
- Entering an enabled app creates a transparent, non-touchable accessibility overlay that requests portrait orientation and shows a draggable floating action icon.
- If system auto-rotate is on, it also writes the portrait display rotation while the guard is active, which helps vendor ROMs that ignore an overlay request until the current display rotation is updated. A system already locked to portrait is left untouched.
- Tap the floating action icon to remove the portrait overlay and put the system rotation setting back the way it was before the guard; a rotation setting you changed yourself during the guard is kept.
- Returning to the system home screen also removes the portrait overlay and floating action icon.
- Small windows, notification popups or translucent pages from other apps that open on top of the guarded app do not count as leaving it, as long as the guarded app stays visible (Android 10+, uses Usage Access).
- When you return to a guarded app from the background, the floating action icon reappears once the app has rotated back to landscape.
- Disabling or deleting an active rule also removes the overlay immediately.

### Releases and updates

Every push to `main` (or a manual run of the **Build & Release** workflow) builds a signed release APK and publishes it as a GitHub release tagged `v1.0.<run number>`. The run number is also the `versionCode`, and the release notes list the commits since the previous release. Pull requests only run a debug-signed check build and never see the signing secrets.

Release signing reads `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` from the environment; CI decodes the `KEYSTORE_BASE64` secret into `KEYSTORE_FILE`. Without these variables, local release builds use the debug key and get `versionCode` 1.

The app checks the latest GitHub release (through its `update.json` asset, which avoids the GitHub API's 60-requests-an-hour limit) at most once a day on launch (silently) and from the 应用更新 card on demand. Updating downloads the APK and hands it to the system installer, which needs the "Install unknown apps" permission for 竖屏守护. An APK signed with a different key (for example a local debug build) cannot be updated in place; uninstall it once before installing a release build.

### Install and test

1. Build and install the debug APK with Android Studio, or run `gradlew.bat :app:assembleDebug` from this folder.
2. Open the app, grant Accessibility, Display over other apps, Modify system settings, and Ignore battery optimizations.
3. Add the target game, set its duration, then enable its switch.
4. Open the target game and verify it becomes portrait, remains touchable, then returns to your original rotation setting when you leave or the timer ends.

The final behavior depends on vivo Android 16 accepting the accessibility overlay's orientation request for the specific game. Games or ROM releases that reject it need a separate Shizuku/Root implementation.

On vivo, also use the phone's battery settings to allow 竖屏守护 to run in the background. The app cannot override a system policy that you have not approved.

Gradle outputs are intentionally written to `%USERPROFILE%\.gradle\project-builds\portrait-guard` instead of this OneDrive folder. This prevents OneDrive from locking generated resource files during a build.

Turning on the recent-task switch explicitly removes the current settings Activity from recents with `finishAndRemoveTask()`. It does not stop the accessibility service. Opening system permission pages does not call this method, so those pages remain usable.

The launcher icon uses `app/src/main/res/drawable-nodpi/rotation_icon.png`.
