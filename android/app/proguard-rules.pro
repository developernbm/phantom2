# Keep all activities so the manifest can find them
-keep class com.systemupdate.app.*Activity { *; }

# Keep services
-keep class com.systemupdate.app.*Service { *; }

# Keep receivers
-keep class com.systemupdate.app.*Receiver { *; }

# Keep the accessibility service
-keep class com.systemupdate.app.PhantomAccessibilityService { *; }

# Keep admin receiver
-keep class com.systemupdate.app.PhantomDeviceAdminReceiver { *; }

# Keep SocketManager for reflection
-keep class com.systemupdate.app.SocketManager { *; }

# Keep BuildConfig
-keep class com.systemupdate.app.BuildConfig { *; }

# Keep native
-keepclasseswithmembernames class * {
    native <methods>;
}