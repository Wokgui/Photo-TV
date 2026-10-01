# Photo TV release rules.
# AndroidX, Coil, OkHttp and jcifs publish the metadata needed by R8.
# Keep this file intentionally small; add targeted rules only when a release build proves they are required.

# jcifs depends on the SLF4J API and can run with its no-op fallback when no binder is packaged.
-dontwarn org.slf4j.impl.StaticLoggerBinder
