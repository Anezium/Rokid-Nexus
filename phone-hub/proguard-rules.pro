# apksig parses ASN.1 through reflection; preserve it if minification is enabled (currently disabled).
-keep class com.android.apksig.** { *; }
-keepattributes *Annotation*
