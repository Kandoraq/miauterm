# Miauterm ProGuard Rules

# Keep native methods and class structure for PtyNative JNI bindings
-keep class mdr.Kandoraq.miauterm.pty.PtyNative {
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

-keep interface mdr.Kandoraq.miauterm.pty.PtyNative$ExtractionProgress { *; }
