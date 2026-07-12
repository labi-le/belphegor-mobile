# gomobile-generated bindings (belphegor.mobile.*) and the Go runtime
# support classes (go.*) are reached over JNI / by name, so R8 must not strip
# or rename them.
-keep class go.** { *; }
-keep class belphegor.mobile.** { *; }
-dontwarn go.**
