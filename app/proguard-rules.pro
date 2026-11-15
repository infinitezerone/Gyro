# --- JNI boundary: libgyro_engine.so (Rust) resolves these names at runtime ---
# RegisterNatives binds the external methods, and the event/property callbacks
# (onEvent / onEndFile / onProperty*) are resolved by name through GetStaticMethodID
# inside the Rust event pump - neither survives R8 renaming. Keep the whole class.
-keep class dev.gyro.engine.GyroNative { *; }

# --- kotlinx.serialization: serializers are resolved reflectively via Companion ---
# UserSettings flows through DataStore with custom KSerializer instances, and the
# navigation routes are @Serializable. Without these rules DataStore silently loses
# the ability to deserialize persisted settings after a rename.
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.gyro.**$$serializer { *; }
-keepclassmembers class dev.gyro.** {
    *** Companion;
}
-keepclasseswithmembers class dev.gyro.** {
    kotlinx.serialization.KSerializer serializer(...);
}
