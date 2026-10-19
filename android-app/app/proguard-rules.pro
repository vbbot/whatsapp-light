# gomobile bindings: the Go side calls these classes and their methods by name over JNI
# (including our implementations of walib.Listener / Speaker / VideoRenderer).
-keep class go.** { *; }
-keep class vbh.ai.whatsapp.walib.** { *; }
-keep class * implements vbh.ai.whatsapp.walib.Listener { *; }
-keep class * implements vbh.ai.whatsapp.walib.Speaker { *; }
-keep class * implements vbh.ai.whatsapp.walib.VideoRenderer { *; }

# Keep line numbers for readable crash reports.
-keepattributes SourceFile,LineNumberTable
