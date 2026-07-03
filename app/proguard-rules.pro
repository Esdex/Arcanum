# ── Stack traces ─────────────────────────────────────────────────────────────
# Keep line numbers for readable crash reports; remap source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── JNI / VeraCryptEngine ────────────────────────────────────────────────────
# The JNI runtime resolves native methods by their mangled name at load time.
# R8 must not rename or strip any class or method that has a `native` modifier.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
# Ensure VeraCryptEngine and its Companion are fully preserved.
-keep class zip.arcanum.crypto.VeraCryptEngine { *; }
-keep class zip.arcanum.crypto.VeraCryptEngine$Companion { *; }
# VolumeGeometry is instantiated from C++ via JNI NewObject. Keeping the outer
# class is not enough for R8, so preserve the nested data class constructor too.
-keep class zip.arcanum.crypto.VeraCryptEngine$VolumeGeometry { *; }
# NativeFileInfo is instantiated by C++ via JNI NewObject — constructor
# signature (String,String,long,boolean,long) must not be renamed or removed.
-keep class zip.arcanum.crypto.NativeFileInfo { *; }
# NativeContainer and CryptoResult are accessed by name from JNI callbacks.
-keep class zip.arcanum.crypto.NativeContainer { *; }
-keep class zip.arcanum.crypto.CryptoResult { *; }
-keep class zip.arcanum.crypto.CryptoError { *; }
# CreationProgressListener is called back from C++ via JNI — onProgress(float,float,long)
# must not be renamed.
-keep interface zip.arcanum.crypto.VeraCryptEngine$CreationProgressListener { *; }
-keep class zip.arcanum.crypto.VeraCryptEngine$CreationProgressListener { *; }

# MountProgressListener is called back from C++ via JNI — onTrying(String,String,int,int)
# must not be renamed.
-keep interface zip.arcanum.crypto.VeraCryptEngine$MountProgressListener { *; }
-keep class zip.arcanum.crypto.VeraCryptEngine$MountProgressListener { *; }

# ── kotlinx.serialization ────────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-dontwarn kotlinx.serialization.**
# Keep generated $$serializer objects (one per @Serializable class).
-keepclassmembers class **$$serializer {
    static **$$serializer INSTANCE;
}
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# ── Room ─────────────────────────────────────────────────────────────────────
# Room generates implementations via KSP but accesses entities/DAOs by
# reflection at runtime to build queries.
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.**

# ── Hilt / Dagger ────────────────────────────────────────────────────────────
# Hilt generates component code at compile time; keep entry-point annotations
# and injection markers so the generated glue compiles correctly with R8.
-keepattributes RuntimeVisibleAnnotations
-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class *
-keep @dagger.hilt.android.AndroidEntryPoint class *
-dontwarn dagger.hilt.**
-dontwarn javax.inject.**

# ── WorkManager ──────────────────────────────────────────────────────────────
# WorkManager reinstantiates workers by class name stored in its SQLite DB.
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ── BouncyCastle ─────────────────────────────────────────────────────────────
# BC registers algorithm implementations via reflection through the JCE
# provider mechanism — class names and method signatures must be preserved.
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ── Metadata Extractor (drew) ─────────────────────────────────────────────────
# Loads tag handler classes by reflection from a classpath scan.
-keep class com.drew.** { *; }
-dontwarn com.drew.**

# ── Security Crypto ───────────────────────────────────────────────────────────
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# ── SQLCipher ────────────────────────────────────────────────────────────────
# SQLCipher loads its native library and accesses Java classes via JNI.
-keep class net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**

# ── Coil ─────────────────────────────────────────────────────────────────────
-dontwarn coil.**

# ── Lottie ───────────────────────────────────────────────────────────────────
-dontwarn com.airbnb.lottie.**

# ── AboutLibraries ───────────────────────────────────────────────────────────
-keep class com.mikepenz.aboutlibraries.** { *; }
-dontwarn com.mikepenz.aboutlibraries.**
