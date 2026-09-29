# Heads Down release shrinking rules.

# --- No logging in release builds ------------------------------------------------------
# R8 treats these calls as side-effect free and deletes them, so no android.util.Log output
# (and nothing a log could leak: tokens, keys, addresses) ships in a release APK. The app's
# own sources contain no Log calls; this also strips any in bundled libraries.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
}

# --- Crash reports: keep line numbers, hide original file names -------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
