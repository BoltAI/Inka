FROM python:3.9-slim-bookworm

ARG TOOLKIT_REPO=https://github.com/X-rayLaser/pytorch-handwriting-synthesis-toolkit.git
ARG TOOLKIT_REF=main
ARG INKA_HANDWRITING_ENGINE=pytorch

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    HOST=0.0.0.0 \
    PORT=8787 \
    ENGINE=${INKA_HANDWRITING_ENGINE} \
    TOOLKIT_DIR=/opt/inka-handwriting/toolkit \
    MODEL_DIR=/opt/inka-handwriting/toolkit/checkpoints/Epoch_52 \
    BIAS=0.8 \
    STEPS=1500 \
    DEVICE=cpu \
    SAMPLE_ATTEMPTS=8

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/inka

COPY scripts/handwriting-server.py /opt/inka/scripts/handwriting-server.py
COPY scripts/docker-handwriting-entrypoint.sh /usr/local/bin/inka-handwriting-server

RUN chmod +x /usr/local/bin/inka-handwriting-server \
    && if [ "$INKA_HANDWRITING_ENGINE" = "pytorch" ]; then \
        mkdir -p "$(dirname "$TOOLKIT_DIR")" \
        && git clone --depth 1 --branch "$TOOLKIT_REF" "$TOOLKIT_REPO" "$TOOLKIT_DIR" \
        && python -m pip install --upgrade pip \
        && python -m pip install --no-cache-dir -r "$TOOLKIT_DIR/requirements.txt"; \
    else \
        python -m pip install --upgrade pip; \
    fi

EXPOSE 8787

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD python -c "import os, urllib.request; urllib.request.urlopen('http://127.0.0.1:%s/health' % os.environ.get('PORT', '8787'), timeout=3).read()"

ENTRYPOINT ["inka-handwriting-server"]
