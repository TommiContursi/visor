# ChatGPT plan integration (work in progress)

This fork aims to add an **optional ChatGPT plan-backed assistant** to Visor without changing existing Anthropic API-key or OpenAI-compatible modes. The existing voice and Bluetooth paths should remain intact.

## What this branch contains

- `siwc/ChatGptAuthorization.kt`: dynamic OSS OAuth authorization request with PKCE, nonce, state, loopback callback validation and registration client-ID handling.
- `siwc/ResponsesSse.kt`: small SSE event framer.
- `siwc/ChatGptPlanClient.kt`: **text-only** model-list and Responses streaming client for a caller-supplied, **already verified and authorized** access token.
- `tools/siwc/ProtocolTests.kt`: standalone Kotlin protocol smoke checks.

These primitives are **not connected to Visor's UI, Brain, or glasses**, and are deliberately not exposed for runtime use yet.

Local standalone test (requires Kotlin CLI):

```sh
kotlinc \
  core/assistant/src/main/kotlin/com/macedonga/visor/core/assistant/siwc/ChatGptAuthorization.kt \
  core/assistant/src/main/kotlin/com/macedonga/visor/core/assistant/siwc/ResponsesSse.kt \
  tools/siwc/ProtocolTests.kt -include-runtime -d /tmp/visor-siwc-tests.jar
java -jar /tmp/visor-siwc-tests.jar
```

This smoke test does **not** compile the Android project, make real OAuth requests, verify signed tokens or test real glasses.

## Requirements before enabling subscription sign-in

1. Start a single-use localhost listener on `127.0.0.1` (not a public network interface), use the Android browser for OAuth, and shut down the listener on timeout/cancellation.
2. Exchange the authorization code with the callback-issued client ID and original PKCE verifier; validate OpenAI's signed ID token with JWKS, issuer, issued client ID as audience, expiry, nonce, and account identity.
3. Only use the plan inference endpoint if the **granted** scopes contain `chatgpt.tokens.use.direct`. Store tokens and account data in protected Android app storage, refresh them safely, and implement account selection and sign-out. Never log authorization codes, ID tokens, or access/refresh tokens.
4. Add an explicit ChatGPT-plan provider/auth mode in Visor settings; do not reuse or mistake ordinary OpenAI API keys for OAuth tokens.
5. Wire model discovery and Responses inference into the existing `Brain`; keep memory, image input, and tools out of scope for the first text-only smoke, then add them with tests.
6. Validate phone text exchange, `Hey Meta`/Oakley Meta glasses routing, reauthentication, sign-out, denied scopes, rate limits, offline behavior, account changes, and absence of surprise paid-API fallback.

**Acceptance for a working MVP:** Android sign-in completes using a real ChatGPT account; the user explicitly approves plan use; an eligible model appears in settings; a question is answered through the glasses without an API key; denied or expired authorization fails clearly.

## Claude and voice

- The existing Claude **API-key** provider remains unchanged. Do not route Claude Pro/Max/Team subscription session tokens through this Android app. A general-purpose consumer subscription auth integration is not offered for this use.
- For voice, first validate Visor's existing **phone-generated Android TTS** by setting `glassesVoice=false`; it already encodes Opus for playback. ChatGPT plan OAuth does not automatically include OpenAI Realtime or speech APIs. Higher-quality voice is a later, separately authorized feature.

## Official references

- https://developers.openai.com/siwc/token-sharing-open-source
- https://developers.openai.com/siwc/token-sharing-open-source/sign-in
- https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference
- https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations
- https://code.claude.com/docs/en/legal-and-compliance

Upstream is AGPL-3.0; preserve its license, attribution and source-availability obligations.
