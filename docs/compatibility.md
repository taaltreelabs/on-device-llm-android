# Compatibility

[Back to the README](../README.md)

## Shared package versions

| Android package | Shared `@taaltreelabs/on-device-llm` peer range |
| --- | --- |
| 1.0.0 (pending publication) | `^1.0.0` |

Android 1.0.0 requires shared 1.0.0 or newer within the 1.x series. Shared 0.x
and 2.x are outside the supported peer range. Upgrade both packages together;
do not use a peer-dependency override to bypass this requirement.
The development dependency and lockfile use shared 1.0.0.

## Runtime and build requirements

Use React Native with Expo native modules in a development or production build.
The example uses Expo SDK 57 and React Native 0.86.3. These describe the example,
not an enforced peer-version floor.

The app must include ML Kit's runtime dependency and target Android API 26 or newer.
AICore and a supported model must be available on the device. Consult Google's
[Prompt API documentation](https://developers.google.com/ml-kit/genai/prompt/android)
for device support; hardware specifications alone do not determine eligibility.

Use `availability()` and `capabilities()` at runtime instead of a hardcoded device
list or model-window assumption. Model availability can change as assets download.

## Provider features

| Feature             | Support                                                         |
| ------------------- | --------------------------------------------------------------- |
| Text generation     | `generate()`                                                    |
| Streaming           | Text deltas through `stream()`                                  |
| Token counting      | Native counting for the encoded request, including role framing |
| Context window      | Read from the model                                             |
| Structured output   | Unsupported; `schema` requests fail as `invalidRequest`         |
| Tool calling        | Unsupported; `tools` requests fail as `invalidRequest`          |
| Locale enumeration  | `UNKNOWN`; no locale configuration option                       |
| Safety refusal code | No native error maps to `guardrail`                             |

Google's SDK features are not automatically features of this bridge. In particular,
its Kotlin structured-output API does not provide runtime JSON Schema support here.

## Conversation encoding

The SDK's content list has no role field. The bridge frames conversation turns as
`User:` / `Model:` and sends a lone user message without a frame. System messages
are combined into the SDK's system instruction when supported, or folded into the
first content item otherwise. Token counting uses this same encoded request.

## Other platforms

Importing the package does not load the native module immediately. iOS, web, Node.js,
and Android builds without the required native integration report `unsupportedPlatform`.
A shared router can select another configured provider in those cases.
