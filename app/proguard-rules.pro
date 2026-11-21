# Release shrinking (R8). Compose, coroutines and AndroidX ship their own keep rules; the app uses
# no reflection, JNI or ServiceLoader.

# Error messages shown to the user include the exception's simple class name.
-keepnames class dev.ririprojector.** extends java.lang.Throwable

# Readable stack traces: retrace them with the mapping.txt attached to each GitHub release.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
