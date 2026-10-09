# OkHttp ships its own consumer rules. Nothing reflective in our code.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# JSch loads ciphers, KEX and signature classes by name from its config.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.apache.logging.**
-dontwarn org.slf4j.**
-dontwarn com.sun.jna.**
-dontwarn org.newsclub.**
# ML Kit barcode scanning (QR pairing): its components are found and wired up by reflection
# (Firebase-style ComponentRegistrar discovery). R8 full mode otherwise strips or renames them and
# the scanner crashes with a NullPointerException as soon as the camera delivers a frame.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode_bundled** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common** { *; }
-keep class com.google.android.odml.** { *; }
-keep class com.google.firebase.components.** { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }
-dontwarn com.google.mlkit.**
