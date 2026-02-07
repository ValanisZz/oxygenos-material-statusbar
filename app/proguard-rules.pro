# Keep Xposed classes
-keep class de.robv.android.xposed.** { *; }
-keep class com.minimed.devoptionshook.** { *; }

# Keep hook methods
-keepclassmembers class * {
    @de.robv.android.xposed.* <methods>;
}
