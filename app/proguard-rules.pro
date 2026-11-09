# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# ---------------------------------------------------------------------------
# PERF A8 / P7 : R8 activé sur les builds release.
#
# MISSA TV charge des extensions a l'execution (DexClassLoader). Elles sont
# compilees contre l'API publique de l'application et resolvent ensuite ces
# classes dans le classloader de l'app : tout ce qu'elles voient doit garder son
# nom, sinon l'extension plante au premier appel (NoSuchMethodError,
# ClassNotFoundException). Les regles ci-dessous sont donc volontairement
# prudentes : l'essentiel du gain vient des bibliotheques tierces (media3,
# Cronet, Jackson, Coil...) que R8 peut elaguer librement.
# ---------------------------------------------------------------------------

# --- API publique exposee aux extensions ---
-keep class com.lagradost.cloudstream3.** { public *; }
-keepclassmembers class com.lagradost.cloudstream3.** { public *; }
-keep class com.lagradost.cloudstream3.MainAPI { *; }
-keep class com.lagradost.cloudstream3.Plugin { *; }

# --- Reseau : app.get() / app.post() renvoient des types OkHttp aux extensions ---
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }
-keep class kotlin.io.encoding.** { *; }

# --- Jsoup : la grande majorite des extensions l'utilisent directement ---
-keep class org.jsoup.** { *; }

# --- Serialisation : parseJson / toJsonLiteral passent par Jackson et
#     kotlinx.serialization, qui cherchent des membres par reflexion ---
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod, Exceptions
-keep class com.fasterxml.jackson.** { *; }
-keepclassmembers class ** {
    @com.fasterxml.jackson.annotation.* *;
}
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- JNI (decodeurs) ---
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- Ressource media3 resolue par son nom (PlayerGestureHelper) ---
-keep class androidx.media3.ui.** { *; }

# --- Classes du Java de bureau referencees par Rhino et Jackson ---
#     (java.beans.*, javax.script.*) : elles n'existent pas sur Android. R8 les
#     signale comme manquantes et fait echouer la build ; on lui demande
#     explicitement de les ignorer plutot que d'abandonner la compilation.
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn org.mozilla.javascript.**
-dontwarn com.fasterxml.jackson.databind.ext.**

# --- Lisibilite des rapports d'erreur ---
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
