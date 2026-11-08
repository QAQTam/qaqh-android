# JNA resolves native methods and UniFFI structures by name at runtime.
-keep class com.sun.jna.** { *; }
-keep class com.qaqh.harness.core.ffi.** { *; }

# The JNA AAR includes desktop window helpers that Android never calls.
-dontwarn java.awt.Component
-dontwarn java.awt.GraphicsEnvironment
-dontwarn java.awt.HeadlessException
-dontwarn java.awt.Window
