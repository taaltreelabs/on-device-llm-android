/**
 * The register-item-12 rig, and the first thing a real consumer would write.
 *
 * `./gradlew testDebugUnitTest` / `assembleDebug` (run from `example/android`)
 * already prove the Kotlin half compiles against the real `genai-prompt`
 * artifacts and passes its 60 JVM tests — see `example/spike/` for the
 * hardware-spike harness that answers everything a JVM test cannot. This
 * screen's job is different and much smaller: it is exactly what
 * DECISIONS.md's register item 12 still owes ("autolinking did not register
 * the module, or the name is not `OnDeviceLlmAndroid`" — the one thing no
 * JVM test or instrumentation test can check, because it needs a real JS
 * runtime talking to Expo's module registry).
 *
 * `createAndroidProvider()`, `availability()`, `capabilities()` and
 * `generate()` are called exactly as documented in the package README's
 * "Usage" section — nothing here is example-only API. On any device or
 * emulator without AICore (which is every emulator, and most physical
 * devices — see README's "Device eligibility"), this MUST render the mapped
 * `unavailable` / `unsupportedPlatform` state cleanly rather than crash or
 * hang: that clean-degradation path is the one every non-allowlisted
 * install of a consumer app actually takes, so it is this rig's real job to
 * exercise it, not a fallback for when the interesting case is unavailable.
 *
 * Plain React Native components, no extra dependencies — matching the main
 * package's own example panel style (dark JSON panel, plain `Button`s, no
 * component library) rather than reaching for one just for this screen.
 */

import { isLLMError } from '@taaltreelabs/on-device-llm/core';
import { createAndroidProvider } from '@taaltreelabs/on-device-llm-android';
import { useCallback, useEffect, useState } from 'react';
import {
  ActivityIndicator,
  Button,
  Platform,
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';

const provider = createAndroidProvider();

type CheckState =
  | { readonly status: 'checking' }
  | { readonly status: 'done'; readonly availabilityJson: string; readonly capabilitiesJson: string }
  | { readonly status: 'error'; readonly message: string };

type GenerateState =
  | { readonly status: 'idle' }
  | { readonly status: 'generating' }
  | { readonly status: 'result'; readonly text: string }
  | { readonly status: 'error'; readonly code: string; readonly message: string };

export default function App() {
  const [check, setCheck] = useState<CheckState>({ status: 'checking' });
  const [generation, setGeneration] = useState<GenerateState>({ status: 'idle' });

  const runCheck = useCallback(() => {
    setCheck({ status: 'checking' });
    void (async () => {
      try {
        // `availability()` and `capabilities()` never throw for a merely
        // unavailable provider (see the LLMProvider contract) -- both are
        // safe to call unconditionally on every platform, every build, and
        // every device, allowlisted or not.
        const [availability, capabilities] = await Promise.all([
          provider.availability(),
          provider.capabilities(),
        ]);
        setCheck({
          status: 'done',
          availabilityJson: JSON.stringify(availability, null, 2),
          capabilitiesJson: JSON.stringify(capabilities, null, 2),
        });
      } catch (err) {
        // Reaching here at all would itself be a finding: the contract says
        // availability()/capabilities() do not throw. Rendered rather than
        // swallowed so that surprise is visible on screen, not just in logcat.
        setCheck({ status: 'error', message: err instanceof Error ? err.message : String(err) });
      }
    })();
  }, []);

  // eslint-disable-next-line react-hooks/exhaustive-deps -- runCheck is stable (empty deps, useCallback)
  useEffect(() => {
    runCheck();
    // Deliberately no cleanup/cancellation: this call is idempotent (it only
    // ever replaces state with its own latest read) and the screen has
    // nowhere else to navigate to that would make an in-flight call stale.
  }, []);

  const runGenerate = useCallback(() => {
    setGeneration({ status: 'generating' });
    void (async () => {
      try {
        const result = await provider.generate({
          messages: [{ role: 'user', content: 'Say OK' }],
        });
        setGeneration({ status: 'result', text: result.text });
      } catch (err) {
        // Every provider failure is an `LLMError` (see the LLMProvider
        // contract's "Throw only LLMError"); on a non-allowlisted device or a
        // build without the ML Kit dependency, this is the
        // unavailable/unsupportedPlatform path -- the one this rig exists to
        // prove renders cleanly instead of crashing the app.
        if (isLLMError(err)) {
          setGeneration({
            status: 'error',
            code: err.details.code,
            message: err.message,
          });
        } else {
          setGeneration({
            status: 'error',
            code: 'unknown',
            message: err instanceof Error ? err.message : String(err),
          });
        }
      }
    })();
  }, []);

  return (
    <SafeAreaView style={styles.safeArea}>
      <ScrollView style={styles.flex} contentContainerStyle={styles.scrollContent}>
        <Text style={styles.header}>on-device-llm-android</Text>
        <Text style={styles.subheader}>providerId: {provider.id}</Text>

        <Panel
          title="availability() / capabilities()"
          loading={check.status === 'checking'}
          onRefresh={runCheck}>
          {check.status === 'checking' ? (
            <Text style={styles.panelJson}>checking…</Text>
          ) : check.status === 'error' ? (
            <Text style={styles.panelError}>threw unexpectedly: {check.message}</Text>
          ) : (
            <>
              <Text style={styles.panelJson}>{check.availabilityJson}</Text>
              <Text style={styles.panelJson}>{check.capabilitiesJson}</Text>
            </>
          )}
        </Panel>

        <View style={styles.generateRow}>
          <Button
            title="Generate"
            onPress={runGenerate}
            disabled={generation.status === 'generating'}
          />
          {generation.status === 'generating' ? <ActivityIndicator size="small" /> : null}
        </View>

        {generation.status === 'result' || generation.status === 'error' ? (
          <View style={styles.resultPanel}>
            <Text style={styles.panelTitle}>generate({'{'}messages: [{'{'} role: 'user', content: 'Say OK' {'}'}]{'}'})</Text>
            {generation.status === 'result' ? (
              <Text style={styles.panelJson}>{generation.text}</Text>
            ) : (
              <Text style={styles.panelError}>
                {generation.code}: {generation.message}
              </Text>
            )}
          </View>
        ) : null}
      </ScrollView>
    </SafeAreaView>
  );
}

function Panel(props: {
  readonly title: string;
  readonly loading: boolean;
  readonly onRefresh: () => void;
  readonly children: React.ReactNode;
}) {
  return (
    <View style={styles.panel}>
      <View style={styles.panelHeader}>
        <Text style={styles.panelTitle}>{props.title}</Text>
        {props.loading ? (
          <ActivityIndicator size="small" />
        ) : (
          <Button title="Refresh" onPress={props.onRefresh} />
        )}
      </View>
      <ScrollView style={styles.panelBody} nestedScrollEnabled>
        {props.children}
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  safeArea: { flex: 1, backgroundColor: '#f3f4f6' },
  flex: { flex: 1 },
  scrollContent: { paddingBottom: 24 },
  header: { fontSize: 24, fontWeight: '700', margin: 20, marginBottom: 4 },
  subheader: { fontSize: 12, color: '#4b5563', marginHorizontal: 20, marginBottom: 12 },
  panel: {
    marginHorizontal: 20,
    marginBottom: 12,
    padding: 12,
    backgroundColor: '#111827',
    borderRadius: 10,
  },
  panelHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 8,
  },
  panelBody: { maxHeight: 320 },
  panelTitle: { color: '#e5e7eb', fontWeight: '600', fontSize: 13, flexShrink: 1 },
  panelJson: {
    color: '#a7f3d0',
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
    fontSize: 11,
    marginBottom: 8,
  },
  panelError: {
    color: '#f87171',
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
    fontSize: 12,
  },
  generateRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    marginHorizontal: 20,
    marginBottom: 12,
  },
  resultPanel: {
    marginHorizontal: 20,
    padding: 12,
    backgroundColor: '#111827',
    borderRadius: 10,
  },
});
