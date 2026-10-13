# 竖屏守护

Self-use Android tool for temporarily forcing selected foreground apps into portrait orientation.

## Behavior

- Configure multiple launchable apps independently.
- Each rule has its own enable switch and post-unlock floating-button setting.
- Entering an enabled app creates a transparent, non-touchable accessibility overlay that requests portrait orientation and shows a draggable floating action icon.
- It also writes the portrait display rotation while the guard is active, which helps vendor ROMs that ignore an overlay request until the current display rotation is updated.
- Tap the floating action icon to remove the portrait overlay and restore automatic system rotation.
- Returning to the system home screen also removes the portrait overlay and floating action icon.
- Disabling or deleting an active rule also removes the overlay immediately.

## Releases and updates

Every push to `main` (or a manual run of the **Build & Release** workflow) builds a signed release APK and publishes it as a GitHub release tagged `v1.0.<run number>`. The run number is also the `versionCode`, and the release notes list the commits since the previous release. Pull requests only run a check build.

Release signing reads `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` from the environment; CI decodes the `KEYSTORE_BASE64` secret into `KEYSTORE_FILE`. Without these variables, local release builds use the debug key and get `versionCode` 1.

The app checks the latest GitHub release at most once a day on launch (silently) and from the 应用更新 card on demand. Updating downloads the APK and hands it to the system installer, which needs the "Install unknown apps" permission for 竖屏守护. An APK signed with a different key (for example a local debug build) cannot be updated in place; uninstall it once before installing a release build.

## Install and test

1. Build and install the debug APK with Android Studio, or run `gradlew.bat :app:assembleDebug` from this folder.
2. Open the app, grant Accessibility, Display over other apps, Modify system settings, and Ignore battery optimizations.
3. Add the target game, set its duration, then enable its switch.
4. Open the target game and verify it becomes portrait, remains touchable, then returns to automatic rotation when you leave or the timer ends.

The final behavior depends on vivo Android 16 accepting the accessibility overlay's orientation request for the specific game. Games or ROM releases that reject it need a separate Shizuku/Root implementation.

On vivo, also use the phone's battery settings to allow 竖屏守护 to run in the background. The app cannot override a system policy that you have not approved.

Gradle outputs are intentionally written to `%USERPROFILE%\.gradle\project-builds\portrait-guard` instead of this OneDrive folder. This prevents OneDrive from locking generated resource files during a build.

Turning on the recent-task switch explicitly removes the current settings Activity from recents with `finishAndRemoveTask()`. It does not stop the accessibility service. Opening system permission pages does not call this method, so those pages remain usable.

The launcher icon uses `app/src/main/res/drawable-nodpi/rotation_icon.png`.
