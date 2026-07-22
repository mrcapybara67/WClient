-dontwarn **
-renamesourcefileattribute null

# Core WClient / WRelay classes referenced by the relay and UI
-keep class com.retrivedmods.wclient.** { *; }
-keep class com.retrivedmods.wrelay.** { *; }

# Netty / Cloudburst protocol use a lot of reflection
-keep class io.netty.** { *; }
-keep class org.cloudburstmc.netty.** { *; }
-keep class org.cloudburstmc.protocol.** { *; }
-keep class org.cloudburstmc.nbt.** { *; }
-keep class org.cloudburstmc.math.** { *; }
-keep @io.netty.channel.ChannelHandler$Sharable class *

# Gson/Jackson serialized fields
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.fasterxml.jackson.** { *; }

# Auth / crypto libraries
-keep class net.raphimc.minecraftauth.** { *; }
-keep class net.lenni0451.commons.httpclient.** { *; }
-keep class org.bitbucket.b_c.** { *; }
-keep class com.radiantbyte.novaclient.game.AccountManager { *; }

# Kotlin coroutines / serialization
-keep class kotlinx.coroutines.** { *; }
-keep class kotlinx.serialization.** { *; }
-keepattributes *Annotation*
