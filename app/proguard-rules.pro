# ProGuard / R8 rules for LoopTimer
#
# NOTE: minification is currently DISABLED in app/build.gradle.kts (release.isMinifyEnabled=false).
#
# The previous version of this file contained:
#     -keep class com.looptimer.** { *; }
#     -keep class androidx.compose.** { *; }
# Those blanket rules kept essentially the entire app and Compose runtime, which meant the build
# accepted all of R8's crash risk while gaining no size or obfuscation benefit.
#
# When re-enabling minification, do NOT reintroduce blanket -keep rules. Instead keep only what
# reflection actually requires and verify a real release APK end-to-end before shipping.
