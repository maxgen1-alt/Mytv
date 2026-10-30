# MyTV uses no reflection or serialization of its own classes.
# OkHttp, Coil, coroutines and Compose ship their own R8 rules.

# Keep line numbers so crash reports stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Wireless debugging: libadb finds Conscrypt's provider by name, and BouncyCastle /
# Conscrypt reference desktop-Java classes Android doesn't have.
-keep class org.conscrypt.** { *; }
-keep class io.github.muntashirakon.adb.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn io.github.muntashirakon.**
-dontwarn javax.naming.**
