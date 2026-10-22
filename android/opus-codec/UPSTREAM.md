# Upstream codec attribution

This module builds the Xiph.Org Foundation's libopus **1.5.2** source from
`src/main/cpp/opus/`. The upstream `COPYING`, `AUTHORS`, and source notices are
retained. The JNI adapter configures 48 kHz stereo music encoding at 192 kbit/s
with VBR and the constrained-VBR control enabled, and exposes decoder packet-loss
concealment for missing frames.

Upstream: https://github.com/xiph/opus/tree/v1.5.2

## Vendored source exclusions

The upstream `dnn/torch/` subtree is omitted. It contains Python model-training,
evaluation and weight-export tools, including separate pip dependency manifests;
these are not inputs to the Android CMake codec/JNI build. Retaining those tools
would introduce an unused Python development environment with vulnerable pinned
dependencies (including GitPython 3.1.41).

When refreshing the upstream source, continue to exclude `dnn/torch/`. Keep the
codec C/C++ sources, generated model data used by CMake, and upstream license and
attribution files. DRED and OSCE remain disabled in the Android build. The
dependency-review workflow continues to scan the remaining dependency manifests.
