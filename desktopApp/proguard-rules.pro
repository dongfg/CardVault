# SQLite loads its driver through JDBC service discovery and calls Java methods
# from native code. Keep its small Java layer, including JNI callbacks, intact.
-keep class org.sqlite.** { *; }

# SQLite 3.53 uses java.util.logging when its optional SLF4J backend is absent.
-dontwarn org.slf4j.Logger
-dontwarn org.slf4j.LoggerFactory
