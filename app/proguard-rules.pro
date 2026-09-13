# OkHttp/Okio reference optional JVM-only providers that are absent on Android.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Tink (under EncryptedSharedPreferences) references errorprone's compile-time annotations,
# which are not packaged at runtime. They were previously on the classpath only because the
# Anthropic SDK pulled them in transitively.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# LuaJ ships a JSR-223 script engine and a JVM-bytecode compiler for desktop Java. Neither
# exists on Android and neither is reachable from Scripts.kt, so R8 drops them; these only
# stop it warning about the references on the way out.
-dontwarn javax.script.**
-dontwarn org.apache.bcel.**
-dontwarn org.luaj.vm2.luajc.**
