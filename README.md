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
