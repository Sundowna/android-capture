# Whisper ONNX pipeline (RTranslator / Whisper+)

Notes for reimplementing the pipeline. Taken from RTranslator `v3.00` (`Recognizer.java`, `TensorUtils.java`), Apache-2.0, and cross-checked against whisperIMEplus `com/whisperonnx/voice_translation/neural_networks/voice/Recognizer.java`. Shapes come from the Java call sites, not from the graphs; check them with Netron when the model is available.

Model: Whisper-small (244M) int8 with KV cache. 12 decoder layers, head dim 64. All sessions register `OrtxPackage.getLibraryPath()` (onnxruntime-extensions).

| File | Input | Output used |
|---|---|---|
| `Whisper_initializer.onnx` | `audio_pcm` float32 `[1, N]`, 16 kHz mono in [-1, 1] | index 0 → encoder (log-mel inside the graph, presumably) |
| `Whisper_encoder.onnx` | `input_features` | index 0, float32 `[1, T, D]` |
| `Whisper_cache_initializer.onnx` | `encoder_hidden_states` | `present.{i}.encoder.key/value`, i = 0..11 |
| `Whisper_decoder.onnx` | `input_ids` int64 `[1,1]`, `past_key_values.{i}.decoder.key/value` float32 `[1,12,L,64]` (L = 0 at first step), `past_key_values.{i}.encoder.key/value` | `logits` `[1,1,vocab]`, `present.{i}.decoder.key/value` |
| `Whisper_detokenizer.onnx` | `sequences` int32 `[1,1,L]` | index 0, string tensor `[0][0]` |
| `Whisper_cache_initializer_batch.onnx` | batch-2 variant | not needed |

Decoding (greedy):
- Forced prefix: SOT `50258`, language `50258 + index + 1` (en = 50259, de = 50261), transcribe `50359` (translate `50358`), no-timestamps `50363`.
- Auto language: feed SOT, then take the argmax of that step as the language token.
- Then feed the previous argmax each step; stop at EOT `50257`.
- Max tokens: `(seconds) * 30`, capped at 445. Under 1 s → nothing.
- The encoder K/V always comes from the cache initializer; the decoder K/V comes from the previous step's `present.*`.
- Session options: CPU arena off, memory pattern off, `NO_OPT` (detokenizer uses the default level).
- Text cleanup: remove `<|…|> ` tokens, trim, capitalise the first letter.
- Recording limit: one pass covers at most ~30 s (480 000 samples). Longer audio must be split.
