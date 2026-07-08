# Handwriting Synthesis Server Design

Inka can render AI text replies as generated ink strokes by calling a local
PyTorch handwriting server on the same Wi-Fi network as the BOOX tablet.
Generated handwriting is disabled by default; the app falls back to the built-in
font renderer unless the Developer setting points at a stroke server.

## App Contract

Endpoint:

```text
POST /handwriting
```

Request:

```json
{
  "text": "The reply text.",
  "pageWidth": 1404,
  "pageHeight": 1872,
  "left": 96,
  "top": 180,
  "maxWidth": 1212,
  "fontSizeSp": 52.0,
  "strokeWidthMm": 0.30,
  "style": "lab-111111",
  "seed": 111111
}
```

Response:

```json
{
  "strokes": [
    {
      "points": [
        { "x": 100.0, "y": 200.0, "pressure": 0.6, "t": 0 }
      ]
    }
  ]
}
```

The Android app converts the response directly to `InkStroke` values and replays
them on the page. It stores the assistant text for future model context and the
generated strokes for visual history.

## PyTorch Toolkit Engine

The model-backed path targets:

```text
https://github.com/X-rayLaser/pytorch-handwriting-synthesis-toolkit
```

One-command setup and run on the Mac:

```bash
scripts/run-handwriting-server-docker.sh
```

The Docker image includes the toolkit dependencies and starts the Inka
handwriting server with the `checkpoints/Epoch_52` synthesis model.

For local Python iteration without Docker:

```bash
PORT=8878 scripts/run-handwriting-model-server.sh
```

That native runner clones the toolkit into `~/.cache/inka-handwriting/`,
creates a virtualenv there, installs the toolkit dependencies, and starts the
same server contract.

The PyTorch adapter samples whole width-wrapped lines, keeps the model's
natural letter variation, scales strokes uniformly, rebalances very short final
lines, and rejects collapsed/runaway samples instead of substituting fake
glyphs. It also caches a model-generated primer per `style` key so all lines
and replies using the same style during a server session feel like the same
writer. When the optional `seed` field is present, the PyTorch adapter uses it
while creating that cached primer, so the developer lab can randomize writer
styles without changing page layout knobs. If all attempts for a line fail, the
server returns no strokes so the app can fall back to normal text rendering.

The upstream toolkit does not publish a canonical "best seed". It exposes
probability bias and multiple trials, while the actual model sampling remains
stochastic. The lab default seed is `111111` because local benchmarks found it
to be the best balance of readability and latency. Avoid seed `24680`; it has
repeatedly produced slow collapsed samples.

Useful runner overrides:

```bash
PORT=8878 BIAS=0.8 SAMPLE_ATTEMPTS=12 scripts/run-handwriting-model-server.sh
ENGINE=placeholder PORT=8878 scripts/run-handwriting-model-server.sh
INKA_HANDWRITING_REFRESH_DEPS=1 PORT=8878 scripts/run-handwriting-model-server.sh
ENGINE=placeholder scripts/run-handwriting-server-docker.sh
```

## Cloudflare Container Deployment

Cloudflare Workers should only be used as the HTTP router for this feature.
The PyTorch model itself runs in a Cloudflare Container using the repository
`Dockerfile`.

Deployment scaffold:

```bash
cd deploy/cloudflare/handwriting-worker
npm install
npm run deploy
```

The Worker exposes the same public contract:

```text
GET /health
POST /handwriting
```

The Android app can point `Server Endpoint` at the deployed Worker URL. This is
not production-safe until the app and Worker support endpoint authentication or
rate limiting.

## Procedural Smoke-Test Engine

Use this only to verify BOOX networking and stroke replay without installing ML
dependencies:

```bash
ENGINE=placeholder PORT=8878 scripts/run-handwriting-model-server.sh
python3 scripts/handwriting-server.py --host 0.0.0.0 --port 8787 --engine placeholder
```

Manual setup:

```bash
git clone https://github.com/X-rayLaser/pytorch-handwriting-synthesis-toolkit.git ~/Code/pytorch-handwriting-synthesis-toolkit
cd ~/Code/pytorch-handwriting-synthesis-toolkit
python3 -m venv venv
. venv/bin/activate
pip install -r requirements.txt
```

Run the server from the Inka repository with the toolkit virtualenv active:

```bash
python3 scripts/handwriting-server.py \
  --host 0.0.0.0 \
  --port 8787 \
  --engine pytorch \
  --toolkit-dir ~/Code/pytorch-handwriting-synthesis-toolkit \
  --model-dir ~/Code/pytorch-handwriting-synthesis-toolkit/checkpoints/Epoch_52 \
  --bias 0.8 \
  --sample-attempts 8
```

Then set Inka:

```text
Settings -> Developer -> Experimental handwriting -> Hosted synthesis
Settings -> Developer -> Server Endpoint -> http://<server-ip>:8787
```

In debug builds, `Settings -> Developer -> Handwriting Lab` opens a full-screen
lab. Press `Run` to send a sample text request to the configured stroke server
and replay the returned strokes on the page. The lab can randomize the writer
seed and save the generated handwriting size/thickness preferences used by
normal replies.

## App Setup

Generated handwriting is disabled by default. The default writing reply path
uses the built-in font renderer.

To enable hosted generated ink strokes:

```text
Settings -> Developer -> Experimental handwriting -> Hosted synthesis
Settings -> Developer -> Server Endpoint -> http://<server-ip>:8878
```

See `docs/design/handwriting-server-setup.md` for the Python server setup and
manual test guide.

## Notes

- The upstream toolkit is MIT licensed. Keep its license notice when
  distributing a server image or hosted deployment.
- The local HTTP server is private from cloud providers, but traffic is not
  encrypted on the local network.
- A cloud endpoint receives generated reply text. Do not expose a public
  endpoint until the app and server support authentication or another abuse
  control.
- The procedural engine is line-stable and deterministic, but it is not the
  product-quality handwriting path.
- The pretrained toolkit model is generic handwriting, not personal
  handwriting. It gives natural variation between repeated letters, but it does
  not reproduce a specific person's handwriting.
- The `style` request field is treated as a server-session writer key. The
  optional `seed` field makes creation of that cached style reproducible. The
  lab default is `lab-111111` with seed `111111`; normal reply synthesis should
  use a stable writer key if consistent handwriting across a session is desired.
- The PyTorch path retries collapsed samples by default. Increase
  `SAMPLE_ATTEMPTS` if a specific model/checkpoint occasionally returns tiny
  scribbles or runaway lines.
- The generated-strokes path only calls a configured stroke server. No
  synthesis model or PyTorch runtime is bundled in the Android app.
- The PyTorch engine was smoke-tested on macOS arm64 with the toolkit
  `checkpoints/Epoch_52` model and returned bounded page-coordinate strokes.
