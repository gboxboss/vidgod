# Vosk speech recognition uses JNA, which relies on reflection.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# Project model is serialized with kotlinx.serialization.
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-keep,includedescriptorclasses class com.vidgod.editor.**$$serializer { *; }
-keepclassmembers class com.vidgod.editor.** {
    *** Companion;
}
-keepclasseswithmembers class com.vidgod.editor.** {
    kotlinx.serialization.KSerializer serializer(...);
}
