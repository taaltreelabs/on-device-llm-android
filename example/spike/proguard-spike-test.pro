# ============================================================================
#  Test-APK keep rules for the RELEASE (R8) run of the spike suite — T1
# ============================================================================
#
#  When `testBuildType = release` and minification is on, AGP runs R8 over the
#  instrumentation APK too (using the app's mapping so references still line
#  up). The runner finds tests by class name through reflection, and the spike
#  builds its one ML Kit-touching class by name for the same reason the library
#  does, so the whole harness has to keep its names.

-keep class expo.modules.ondevicellmandroid.spike.** { *; }

# JUnit and the AndroidX test runner are entirely reflective.
-keep class androidx.test.** { *; }
-keep class org.junit.** { *; }
-keep @org.junit.runner.RunWith class * { *; }
-keepclassmembers class * {
  @org.junit.Test <methods>;
  @org.junit.Before <methods>;
  @org.junit.After <methods>;
  @org.junit.BeforeClass <methods>;
  @org.junit.AfterClass <methods>;
}
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

-dontwarn org.junit.**
-dontwarn androidx.test.**
