# Miauterm ProGuard Rules

# Keep native methods and class structure for PtyNative JNI bindings
-keep class salu.Kandoraq.Miauterm.pty.PtyNative {
    native <methods>;
    *;
}

# Keep DrawerLayout internal fields accessed via reflection in MainActivity
-keepclassmembers class androidx.drawerlayout.widget.DrawerLayout {
    private androidx.customview.widget.ViewDragHelper mLeftDragger;
}
-keepclassmembers class androidx.customview.widget.ViewDragHelper {
    private int mEdgeSize;
}

-keep interface salu.Kandoraq.Miauterm.pty.PtyNative$ExtractionProgress { *; }
