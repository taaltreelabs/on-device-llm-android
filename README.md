<p align="center">
  <a href="https://taaltreelabs.com">
    <img src="docs/assets/taaltree-labs.svg" alt="TaalTree Labs" width="88" height="88">
  </a>
</p>

# @taaltreelabs/on-device-llm-android

On-device text generation for React Native and Expo on Android, powered by Gemini Nano through Google's ML Kit and AICore.

Use it directly or add it to the shared `on-device-llm` router alongside Apple and cloud providers.

[Documentation](https://taaltreelabs.com/docs/on-device-llm-android/) ·
[Example app](example) ·
[Report an issue](https://github.com/taaltreelabs/on-device-llm-android/issues)

## Features

- **On-device text generation and streaming** through the same `LLMProvider` interface as the Apple package.
- **Availability and capability checks** for device eligibility, model readiness, and supported features.
- **Native token counting** for the request sent to the model.
- **Shared routing and React hooks** from `@taaltreelabs/on-device-llm`.
- **Explicit SDK installation:** your app chooses whether to include Google's native runtime.

## Requirements

| Requirement         | What to use                                                                                                |
| ------------------- | ---------------------------------------------------------------------------------------------------------- |
| Device              | An eligible Android device with AICore and model availability; call `availability()` at runtime            |
| Android minimum SDK | API 26 or newer when including ML Kit                                                                      |
| App build           | React Native with Expo modules in a native development or production build; Expo Go cannot load the module |
| Google SDK          | `com.google.mlkit:genai-prompt:1.0.0-beta4`, added by the config plugin below                              |
| Shared package      | `@taaltreelabs/on-device-llm`; see [version compatibility](docs/compatibility.md#shared-package-versions)  |

The example app uses Expo SDK 57 and React Native 0.86.3. AICore eligibility depends on
Google's supported devices, not just RAM or chipset. Check the
[Prompt API documentation](https://developers.google.com/ml-kit/genai/prompt/android)
and use an eligible physical device for model inference. An emulator can exercise
UI and unavailable-provider behavior.

**Privacy:** inference runs on-device, but ML Kit sends usage and performance metrics
to Google. Review [privacy and SDK terms](docs/privacy.md) before adding the SDK.

## Quick start

### 1. Install both packages

For Android 1.0.0 (pending publication):

```bash
npm install @taaltreelabs/on-device-llm@^1.0.0 @taaltreelabs/on-device-llm-android@^1.0.0
```

The shared package supplies the router, context manager, and React hooks. The Android
package supplies `createAndroidProvider()` and its native bridge. Android 1.0.0 requires
shared 1.x (`^1.0.0`); shared 0.x is no longer supported. See
[version compatibility](docs/compatibility.md#shared-package-versions).

### 2. Configure your Expo app

Add the plugin to your existing `app.json` plugins list:

```json
{
  "expo": {
    "plugins": [
      [
        "@taaltreelabs/on-device-llm-android",
        {
          "mlKitGenAiPromptVersion": "1.0.0-beta4",
          "minSdkVersion": 26
        }
      ]
    ]
  }
}
```

The plugin adds the ML Kit dependency, sets the minimum Android SDK, and configures
the Kotlin metadata flag required by this SDK/toolchain combination. Both options
default to the values shown. Keep the SDK version pinned.

For manually maintained native projects, follow the [native setup guide](docs/native-setup.md).
For an app that also uses Apple's provider, add `"@taaltreelabs/on-device-llm"` to the
plugins list too; the plugins configure different platforms.

### 3. Add a chat component

This example runs on-device only. `useChat` manages history and context budgets,
exposes streamed text, and reports errors for your UI.

```tsx
import { createAndroidProvider } from '@taaltreelabs/on-device-llm-android';
import { useChat } from '@taaltreelabs/on-device-llm/react';
import { Button, Text, View } from 'react-native';

const android = createAndroidProvider();

export function Assistant() {
  const { messages, streamingText, status, error, send, stop } = useChat({
    provider: android,
    systemPrompt: 'You are a concise assistant.',
  });

  return (
    <View>
      {messages.map((message, index) => (
        <Text key={index}>{message.content}</Text>
      ))}
      {streamingText !== undefined && <Text>{streamingText}</Text>}
      {error && <Text accessibilityRole="alert">Unable to complete the request.</Text>}
      <Button
        title="Ask"
        disabled={status !== 'idle'}
        onPress={() => void send('Explain why leaves change color.')}
      />
      {status !== 'idle' && <Button title="Stop" onPress={stop} />}
    </View>
  );
}
```

### 4. Build and run

```bash
npx expo prebuild --platform android
npx expo run:android --device
```

Native configuration changes require a rebuild. Check availability before offering
an on-device feature; see [availability and troubleshooting](docs/troubleshooting.md).

## Cloud fallback and privacy

To add a fallback, pass a router to `useChat` instead of the Android provider:

```ts
import { createAndroidProvider } from '@taaltreelabs/on-device-llm-android';
import { createRouter } from '@taaltreelabs/on-device-llm/core';
import { createOpenAIProvider } from '@taaltreelabs/on-device-llm/openai';
import { fetch as expoFetch } from 'expo/fetch';

export const llm = createRouter({
  providers: [
    createAndroidProvider(),
    createOpenAIProvider({
      baseUrl: 'https://your-backend.example.com/v1',
      model: 'your-model',
      contextWindow: 128_000, // Use your model's actual limit.
      fetch: expoFetch as unknown as typeof fetch,
    }),
  ],
});
```

Replace the endpoint and model with your own and configure your app's authentication.
Keep vendor secrets on your backend. When selected, the cloud provider receives the
request's conversation content. `expo/fetch` enables cloud streaming in Expo.

Importing the Android provider is safe on other platforms: it resolves the native
module lazily and reports `unsupportedPlatform` where unavailable. You can also add
`createAppleProvider()` to this router for iOS.

There is no mid-stream fallback after the first event reaches the consumer.
The Android error mapper does not emit `guardrail`, so a routing policy based on that
code cannot identify Android safety refusals. See [privacy and fallback](docs/privacy.md).

## Guides

| I want to…                                     | Guide                                                                     |
| ---------------------------------------------- | ------------------------------------------------------------------------- |
| Configure native dependencies manually         | [Native setup](docs/native-setup.md)                                      |
| Check supported features and package versions  | [Compatibility](docs/compatibility.md)                                    |
| Understand SDK metrics and cloud data flow     | [Privacy and SDK terms](docs/privacy.md)                                  |
| Diagnose unavailable devices or build failures | [Troubleshooting](docs/troubleshooting.md)                                |
| Manage context, routing, or streaming          | [Shared integration guides](https://taaltreelabs.com/docs/on-device-llm/) |

Structured output, tool calling, and locale enumeration are not exposed by this
provider. Requests containing `schema` or `tools` fail as `invalidRequest`.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development checks and the native test harness.

## License

[MIT](LICENSE) · Built by [TaalTree Labs](https://taaltreelabs.com).
Google's SDK is installed separately and is governed by its own terms.
