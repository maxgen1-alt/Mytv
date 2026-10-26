# MyTV uses no reflection or serialization of its own classes.
# OkHttp, Coil, coroutines and Compose ship their own R8 rules.

# Keep line numbers so crash reports stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
