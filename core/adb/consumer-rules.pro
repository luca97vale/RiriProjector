# JNI: adb_pairing.cpp registers natives on this class by name.
-keep class dev.ririprojector.adb.PairingContext { native <methods>; *; }
# BouncyCastle is reached through JCA provider lookups.
-dontwarn org.bouncycastle.**
