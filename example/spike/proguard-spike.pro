# ============================================================================
#  App-side keep rules for the wave-2 spike's RELEASE (R8) run — tripwire T1
# ============================================================================
#
#  These are the *harness's* rules, not the library's. The library ships its own
#  in `android/consumer-rules.pro`, and AGP applies those to this app
#  automatically through the autolinked project dependency — which is the whole
#  point of the T1 run: if the release variant needed rules that are NOT in the
#  library's own file, the library would be shipping a broken firewall.
#
#  So: nothing here may keep anything belonging to the library or to ML Kit.
#  If the release run fails and adding a rule here fixes it, that rule belongs
#  in `android/consumer-rules.pro` instead, and that is a finding.

# `Spike12Registration` reads the generated autolinking package list
# reflectively — `expo.modules.ExpoModulesPackageList.getModulesMap()`, whose keys
# are the registered module classes — to check that this package's module was
# registered at all. Obfuscating it would make the test report `inconclusive`
# rather than fail, but a kept name gives a real answer, and this class is
# generated build-time glue with nothing to hide.
-keep class expo.modules.ExpoModulesPackageList { *; }
