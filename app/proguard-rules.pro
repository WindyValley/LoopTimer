# ProGuard / R8 rules for LoopTimer
#
# The previous version of this file contained blanket rules:
#     -keep class com.looptimer.** { *; }
#     -keep class androidx.compose.** { *; }
# Those kept essentially the entire app and Compose runtime, so the build accepted all of R8's
# risk while gaining no size or obfuscation benefit. Do not reintroduce them.
#
# The app uses no reflection, no JNI and no Gson/Moshi-style reflective serialisation
# (persistence goes through explicit org.json field access), so R8's defaults plus the
# AGP-supplied Compose and Kotlin rules are sufficient.

# Keep line numbers so release stack traces stay actionable, while still renaming the file.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
