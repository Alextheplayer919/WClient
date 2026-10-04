-dontwarn **
-renamesourcefileattribute null
-keep class io.netty.** { *; }
-keep class org.cloudburstmc.netty.** { *; }
-keep class org.cloudburstmc.protocol.bedrock.codec.** { *; }
-keep @io.netty.channel.ChannelHandler$Sharable class *
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class net.raphimc.minecraftauth.** { *; }
-keep class net.lenni0451.commons.httpclient.** { *; }
-keep class com.retrivedmods.wclient.game.AccountManager { *; }

# JJWT (MinecraftAuth fallback signer) resolves implementations reflectively / via ServiceLoader.
-keep class io.jsonwebtoken.** { *; }
-keep interface io.jsonwebtoken.** { *; }
-keepnames class io.jsonwebtoken.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# Our JCA provider is instantiated by name by java.security.Signature.
-keep class com.retrivedmods.wclient.util.P1363SignatureProvider { *; }
-keep class com.retrivedmods.wclient.util.P1363SignatureProvider$P1363Spi { *; }
