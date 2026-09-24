/**
 * The example app exists to be a Gradle compile and test host.
 *
 * `./gradlew :taaltreelabs-on-device-llm-android:testDebugUnitTest` and
 * `./gradlew assembleDebug` run from `example/android`, which is how the Kotlin
 * module gets compiled against the real `genai-prompt` artifacts and how its 60
 * JVM tests run. That is the whole job: **this screen has never shown a
 * generated token and cannot on any machine you own.** AICore is a preinstalled
 * system service on no emulator image (docs/research/android-genai.md §6), so the
 * only device that can answer is an allowlisted physical phone.
 *
 * What it does do, usefully, is exercise the honest-unavailable path — which is
 * what every non-allowlisted device, and every build whose app has not added the
 * ML Kit dependency, will actually show a user.
 */

import { createAndroidProvider } from '@taaltreelabs/on-device-llm-android';
import { useEffect, useState } from 'react';
import { SafeAreaView, ScrollView, Text, View } from 'react-native';

const provider = createAndroidProvider();

export default function App() {
  const [lines, setLines] = useState<string[]>(['checking…']);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const availability = await provider.availability();
      const capabilities = await provider.capabilities();
      if (cancelled) return;
      setLines([
        `providerId: ${provider.id}`,
        `available: ${availability.available}`,
        `reason: ${availability.reason ?? '—'}`,
        `detail: ${availability.detail ?? '—'}`,
        `contextWindow: ${String(capabilities.contextWindow)}`,
        `streaming: ${capabilities.streaming}`,
        `structuredOutput: ${capabilities.structuredOutput}`,
        `tools: ${capabilities.tools}`,
        `tokenCounting: ${capabilities.tokenCounting}`,
      ]);
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <SafeAreaView style={styles.container}>
      <ScrollView style={styles.container}>
        <Text style={styles.header}>on-device-llm-android</Text>
        <Group name="availability() / capabilities()">
          {lines.map((line) => (
            <Text key={line} style={styles.line}>
              {line}
            </Text>
          ))}
        </Group>
      </ScrollView>
    </SafeAreaView>
  );
}

function Group(props: { name: string; children: React.ReactNode }) {
  return (
    <View style={styles.group}>
      <Text style={styles.groupHeader}>{props.name}</Text>
      {props.children}
    </View>
  );
}

const styles = {
  header: { fontSize: 28, margin: 20 },
  groupHeader: { fontSize: 18, marginBottom: 12 },
  group: { margin: 20, backgroundColor: '#fff', borderRadius: 10, padding: 20 },
  container: { flex: 1, backgroundColor: '#eee' },
  line: { fontSize: 13, marginBottom: 6 },
};
