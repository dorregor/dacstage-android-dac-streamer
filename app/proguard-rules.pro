# kotlinx.serialization: las clases @Serializable conservan sus serializers generados.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class io.github.dacstage.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
# libadb-android / Conscrypt / BouncyCastle usan reflexión y proveedores JCA.
-keep class io.github.muntashirakon.adb.** { *; }
-keep class org.conscrypt.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn sun.security.**
-dontwarn javax.naming.**
