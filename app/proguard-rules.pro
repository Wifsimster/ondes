# R8 shrinks Media3. The blanket `-keep class androidx.media3.** { *; }` that
# used to live here exempted the app's largest dependency from R8 entirely, so
# the player, extractors, decoders and data sources all shipped whole (opt. 8).
# ExoPlayer and the modules under it carry their own consumer rules for the few
# classes they reach by reflection, so they need nothing from us.
#
# media3-session is the exception: its AAR ships no consumer rules, and it is
# the module other processes talk to (system media controls, Android Auto,
# Wear), through Bundles and AIDL stubs that a shrinker can't see being used.
# It stays kept whole.
#
# Release APK: 4.28 MB with the blanket keep -> 3.43 MB with this rule. Dropping
# the session keep as well would save a further 0.15 MB, which is not worth
# risking the media session on rules nobody publishes.
-dontwarn androidx.media3.**
-keep class androidx.media3.session.** { *; }

# Instantiated by the framework from the manifest, by name.
-keep class ovh.battistella.ondes.playback.PlaybackService { *; }

# Hilt / Dagger generated code is handled by their consumer rules.
# Room generated code is handled by its consumer rules.

# Strip verbose/debug/info logging from release builds so feed URLs, media-
# browser caller identity and browse activity never reach logcat. Genuine
# warnings/errors (Log.w / Log.e) are kept.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
