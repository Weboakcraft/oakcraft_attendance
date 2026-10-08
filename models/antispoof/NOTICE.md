# Anti-spoofing models

`2.7_80x80_MiniFASNetV2/` and `4_0_0_80x80_MiniFASNetV1SE/` are the
Silent-Face-Anti-Spoofing MiniFASNet classifiers
(https://github.com/minivision-ai/Silent-Face-Anti-Spoofing, Apache-2.0),
in TensorFlow.js graph format as published in the `open-face-liveness@0.1.0`
npm package (MIT; model files keep their Apache-2.0 licence).

Input: 1×3×80×80, BGR, 0–255, face box scaled ×2.7 / ×4.0.
Output: logits for [paper, real, screen].
