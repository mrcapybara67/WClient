-dontwarn **
-renamesourcefileattribute null

# ============================================
# Core WClient / WRelay app classes
# ============================================
-keep class com.retrivedmods.wclient.** { *; }
-keep class com.retrivedmods.wrelay.** { *; }

# ============================================
# Netty / Cloudburst protocol (heavy reflection)
# ============================================
-keep class io.netty.** { *; }
-keep class org.cloudburstmc.netty.** { *; }
-keep class org.cloudburstmc.protocol.** { *; }
-keep class org.cloudburstmc.nbt.** { *; }
-keep class org.cloudburstmc.math.** { *; }
-keep @io.netty.channel.ChannelHandler$Sharable class *

# ============================================
# OkHttp / Okio (used by VerificationManager)
# ============================================
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# ============================================
# Gson (used by AccountManager, MappingProvider, etc.)
# ============================================
-keep class com.google.gson.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ============================================
# Jackson (for NBT / JSON parsing in relay)
# ============================================
-keep class com.fasterxml.jackson.** { *; }

# ============================================
# jose4j (JWS/JWT handling in OnlineLoginPacketListener & AuthUtils)
# ============================================
-keep class org.jose4j.** { *; }

# ============================================
# Auth / crypto libraries
# ============================================
-keep class net.raphimc.minecraftauth.** { *; }
-keep class net.lenni0451.commons.httpclient.** { *; }
-keep class org.bitbucket.b_c.** { *; }

# ============================================
# Kotlin coroutines / serialization
# ============================================
-keep class kotlinx.coroutines.** { *; }
-keep class kotlinx.serialization.** { *; }
-keep class kotlin.coroutines.** { *; }

# ============================================
# Kyori Adventure (relay uses Component)
# ============================================
-keep class net.kyori.** { *; }

# ============================================
# Bouncy Castle (MinecraftAuth crypto)
# ============================================
-keep class org.bouncycastle.** { *; }

# ============================================
# Useful attributes
# ============================================
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeInvisibleAnnotations
