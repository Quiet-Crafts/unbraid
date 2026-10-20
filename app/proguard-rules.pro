# gomobile bindings are called from native code
-keep class io.nekohasekai.libbox.** { *; }
-keep class go.** { *; }
-keepclassmembers class app.unbraid.** implements io.nekohasekai.libbox.** { *; }
