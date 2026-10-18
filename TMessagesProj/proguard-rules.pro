-keep public class com.google.android.gms.* { public *; }
-keepnames @com.google.android.gms.common.annotation.KeepName class *
-keepclassmembernames class * {
    @com.google.android.gms.common.annotation.KeepName *;
}

-keep @interface androidx.annotation.Keep
-keep @androidx.annotation.Keep class * { *; }
-keepclasseswithmembers class * { @androidx.annotation.Keep *; }

-keep class org.webrtc.* { *; }
-keep class org.webrtc.audio.* { *; }
-keep class org.webrtc.voiceengine.* { *; }
-keep class org.telegram.messenger.* { *; }
-keep class org.telegram.messenger.camera.* { *; }
-keep class org.telegram.messenger.secretmedia.* { *; }
-keep class org.telegram.messenger.support.* { *; }
-keep class org.telegram.messenger.support.* { *; }
-keep class org.telegram.messenger.time.* { *; }
-keep class org.telegram.messenger.video.* { *; }
-keep class org.telegram.messenger.voip.* { *; }
-keep class org.telegram.SQLite.** { *; }
-keep class org.telegram.tgnet.ConnectionsManager { *; }
-keep class org.telegram.tgnet.NativeByteBuffer { *; }
-keep class org.telegram.tgnet.RequestTimeDelegate { *; }
-keep class org.telegram.tgnet.RequestDelegate { *; }
-keep class org.telegram.ui.Stories.recorder.FfmpegAudioWaveformLoader { *; }
-keep class androidx.mediarouter.app.MediaRouteButton { *; }
-keepclassmembers class ** {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class org.telegram.utils.code.highlight.PrismaHighlighter {
    private static native <methods>;
}

# https://developers.google.com/ml-kit/known-issues#android_issues
-keep class com.google.mlkit.nl.languageid.internal.LanguageIdentificationJni { *; }

# Huawei Services
-keep class com.huawei.hianalytics.**{ *; }
-keep class com.huawei.updatesdk.**{ *; }
-keep class com.huawei.hms.**{ *; }

# Don't warn about checkerframework and Kotlin annotations
-dontwarn org.checkerframework.**
-dontwarn javax.annotation.**

-keep class io.nano.tex.** {*;}

-keep class org.telegram.tgnet.** { *; }

# JLatexMath: macro/atom classes are loaded reflectively by Class.forName
-keep class org.scilab.forge.jlatexmath.** { *; }
-keep class ru.noties.jlatexmath.** { *; }
-dontwarn org.scilab.forge.jlatexmath.**

# Use -keep to explicitly keep any other classes shrinking would remove
# NovaGram: upstream turned R8 optimisation and obfuscation on in 12.10.2, and
# its own 12.10.2 broke on it - 12.10.3 is the fix, a keep for tgnet (called
# from native code by name) and TLClassStore rewritten off reflection. The fork
# keeps both off: its releases keep class names so a user's crash log reads
# without a mapping file, and switching the optimiser on changes code the fork
# has already run on devices. Turning it on is a separate, measured change.
-dontoptimize
-dontobfuscate
# NovaGram: the update bar at the bottom of the chat list is built only through
# upstream's IUpdateLayout hook and is instantiated from the application module,
# so nothing inside this library refers to it and R8 removes it - after which
# the reference from ApplicationLoaderImpl cannot be resolved and the release
# build fails outright.
-keep class org.telegram.messenger.novagram.update.NovaUpdateLayout { *; }

# NovaGram: since 12.10.4 JNI_OnLoad registers the media3 ffmpeg natives by
# name and fails the whole libtmessages load if one is missing. A failed load
# makes NativeLoader map a second copy from files/lib, and the copy that plays
# GIFs and video avatars never saw setJava - a null JavaVM and SIGSEGV in
# requestFd. The 12.10.5 merge built that way over a stale media3 pin; the
# natives are kept by name so R8 can never be the next cause of the same load.
-keepclassmembers class androidx.media3.decoder.ffmpeg.** { native <methods>; }

-keep class org.telegram.tgnet.** { *; }
