# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# gRPC OkHttp + ServiceLoader providers for release minification
-keepclassmembers,allowoptimization class io.grpc.okhttp.OkHttpChannelProvider {
    <init>();
}
-keep class io.grpc.okhttp.OkHttpChannelProvider { *; }
-keep class * extends io.grpc.ManagedChannelProvider { *; }
-keep class * extends io.grpc.NameResolverProvider { *; }
-keep class * extends io.grpc.LoadBalancerProvider { *; }
-dontwarn sun.misc.Unsafe
-dontwarn javax.naming.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn com.google.errorprone.annotations.**

