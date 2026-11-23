# Règles R8 / ProGuard pour l'application Agent CTM

# Sérialisation Gson (CatalogueCache & modèles)
-keepattributes Signature, *Annotation*
-keep class com.ctm.credit.agent.** { *; }
-keep class com.ctm.credit.model.** { *; }
-keep class cd.ctm.agent.data.CatalogueCache$Snapshot { *; }
-keep class com.google.gson.** { *; }

# kotlinx.serialization (Navigation Compose routes typées)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class cd.ctm.agent.ui.navigation.** {
    *** Companion;
}
-keepclasseswithmembers class cd.ctm.agent.ui.navigation.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# SQLCipher natif
-keep class net.zetetic.** { *; }
-dontwarn net.zetetic.**

# OkHttp / Conscrypt optionnels
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
