# DevLoom Ollama image — the models are baked into the image, so a fresh machine
# needs no pull step (unlike the from-source stack's `ollama-init`, which pulls at
# `up`). This is what makes the prebuilt stack portable: clone + up and the models
# are already there.
#
# Build locally (uses your GPU/CPU only to store blobs, not to run):
#   docker build -t ghcr.io/sarasnt/devloom-ollama:latest ./ollama
#   docker build --build-arg MODELS="qwen2.5-coder:7b" -t ... ./ollama   # lighter
#
# Size note: each model adds its full weight to the image — qwen2.5-coder:7b ≈ 4.7 GB,
# gpt-oss:20b ≈ 13 GB. Keep MODELS lean for an image that's quick to pull on a new PC;
# you can always `ollama pull <big-model>` at runtime (it lands in the mounted volume).
FROM ollama/ollama:latest

# Space-separated model tags to bake in. Override with --build-arg MODELS="...".
ARG MODELS="qwen2.5-coder:7b"

# Start the server, wait until it answers, pull each model into /root/.ollama, stop it.
# The pulled blobs are committed into this image layer.
RUN set -eux; \
    ollama serve & \
    pid=$!; \
    until ollama list >/dev/null 2>&1; do sleep 1; done; \
    for m in $MODELS; do echo "==> pull $m"; ollama pull "$m"; done; \
    kill "$pid"; \
    wait "$pid" 2>/dev/null || true

# ENTRYPOINT/CMD (ollama serve) are inherited from the base image.
