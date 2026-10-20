# Upstream codec attribution

This module builds the Xiph.Org Foundation's libopus **1.5.2** source from
`src/main/cpp/opus/`. The upstream `COPYING`, `AUTHORS`, and source notices are
retained. The JNI adapter configures 48 kHz stereo music encoding at 192 kbit/s
with VBR and the constrained-VBR control enabled, and exposes decoder packet-loss
concealment for missing frames.

Upstream: https://github.com/xiph/opus/tree/v1.5.2
