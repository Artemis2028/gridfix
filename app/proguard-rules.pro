# MGRS GPS release rules.
#
# The app has no reflection of its own, but three dependencies do, and R8 cannot
# see through them. Everything here is deliberately conservative: a wrong strip
# shows up as a crash on a tester's phone, not as a build failure.

# --- osmdroid ---------------------------------------------------------------
# Tile sources and overlays are constructed reflectively from configuration,
# and the library ships its own resources/preferences machinery.
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# --- NGA MGRS / grid libraries ----------------------------------------------
# Property files and enum valueOf() lookups drive the grid definitions.
-keep class mil.nga.** { *; }
-dontwarn mil.nga.**

# --- Google Play Billing -----------------------------------------------------
# Billing responses are parsed into library classes by name.
-keep class com.android.billingclient.** { *; }
-dontwarn com.android.billingclient.**

# --- ZXing -------------------------------------------------------------------
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# --- Kotlin / coroutines -----------------------------------------------------
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# Enum valueOf/values() are used by DataStore-backed settings round-trips.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep our own data classes intact: they are serialised to JSON by property
# name in backups and interchange files.
-keep class app.gridfix.android.data.** { *; }

# Line numbers make a Play crash report readable once mapping.txt is uploaded.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
