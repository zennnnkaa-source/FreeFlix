# Nuvola — R8 keep rules.
#
# This file was ZERO BYTES while build.gradle already had `minifyEnabled true`
# and `shrinkResources true`, so the next release build would have run R8 with
# no rules at all. Everything below is reached by NAME at runtime, which R8
# cannot see: it would have renamed or removed each one and the failure would
# have been a release-only crash that never reproduces in debug.

# ---------------------------------------------------------------------------
# Shizuku. AimInjector and TouchWatcher reach the binder and its AIDL stubs
# reflectively, and the library's own provider is named in AndroidManifest.
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# ---------------------------------------------------------------------------
# hiddenapibypass resolves android.hardware.input.IInputManager$Stub by string
# (AimInjector: Class.forName), which is the whole point of the library.
-keep class org.lsposed.hiddenapibypass.** { *; }
-dontwarn android.hardware.input.**

# ---------------------------------------------------------------------------
# Anything the platform instantiates or calls by name.
# AGP generates keeps for manifest components automatically; these cover the
# reflective and callback surfaces it cannot see.
-keepclassmembers class * extends android.app.Service {
    public void onRevoke();
}
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# ---------------------------------------------------------------------------
# Enums restored from persisted preference strings. valueOf/values are
# reflective and R8 strips them when it sees no direct call.
-keepclassmembers enum com.nepdon.wire.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---------------------------------------------------------------------------
# NOT needed, recorded so nobody adds them "just in case":
#   - findViewById is safe. R8 renames classes and methods, never resource ids,
#     which live in resources.arsc.
#   - shrinkResources is safe HERE specifically because the app contains no
#     Resources.getIdentifier() call, so no resource is named dynamically.
#   - No custom View subclass appears in any layout XML, so no -keep is needed
#     for the two-arg (Context, AttributeSet) constructors.
# Re-check all three before assuming they still hold.

# ---------------------------------------------------------------------------
# Phase 0 hardening (2026-09-17, docs/SECURITY_MODEL.md).
#
# R8 protects NAMES only, not the constants/algorithms that are the real crown
# jewels — this is the floor that makes native porting (Phase 2) matter, not a
# defence on its own. Full mode is enabled in gradle.properties.

# Flatten the whole app into the default package and let R8 tighten access so it
# can inline/merge more aggressively. Makes a jadx dump a wall of single-letter
# classes in one package.
-repackageclasses ''
-allowaccessmodification

# Strip android.util.Log calls (and the string literals passed to them, which
# leak intent to a decompiler). DevLogger is already a runtime no-op in
# production (devMode=false); its call sites are left until their exact
# signatures are verified before adding them here.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

# ---------------------------------------------------------------------------
# Google Tink (auth/ config crypto). Under R8 full mode + -repackageclasses +
# -allowaccessmodification, Tink's shaded-protobuf keyset (de)serialization does
# reflective field access — without these keeps SecureStore throws only in a
# release build (the classic "looked fine in debug" failure). OkHttp/Okio ship
# their own consumer rules.
-keep class com.google.crypto.tink.** { *; }
-keep class com.google.crypto.tink.shaded.protobuf.** { *; }
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite { <fields>; }
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------------------------------------------------------------------------
# JNI RegisterNatives target. libnuvola_native's JNI_OnLoad binds its workers by
# the LITERAL class name "com/nuvola/app/net/NativeBridge" and method names
# ("nnSelftest", "crc7Seal", "chainDecode") + descriptors. R8 renaming the class
# or those methods makes RegisterNatives fail ("Failed to register native method
# ...crc7Seal") -> JNI_OnLoad returns JNI_ERR -> System.loadLibrary throws ->
# NativeLoader falls back to Java and the native core is silently DISABLED in
# every release build. Caught on-device 2026-09-18 (debug is un-minified so it
# hid this). Keep the class + all members verbatim, descriptors included.
-keep,includedescriptorclasses class com.nepdon.wire.net.NativeBridge { *; }
