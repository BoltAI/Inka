# Handwriting Server Setup

Inka can optionally call a local Python server to turn AI reply text into ink
strokes. This is experimental and disabled by default. Without this server, the
app uses the built-in font renderer.

The request sends generated reply text to the configured endpoint. Use the local
Docker server for private LAN testing. Treat any cloud endpoint as an explicit
privacy choice.

## Requirements

- A Mac or PC on the same Wi-Fi network as the BOOX tablet.
- Docker Desktop, Colima, or Python 3.9+ for the native runner.
- Enough disk space for the Docker image or cached PyTorch toolkit,
  virtualenv, and checkpoint.
- The Inka repository checked out locally.

## Start With Docker

From the Inka repository:

```bash
scripts/run-handwriting-server-docker.sh
```

This builds the `inka-handwriting-server:local` image and starts the server at:

```text
http://127.0.0.1:8878
```

On macOS, you can also double-click:

```text
scripts/start-handwriting-server-mac.command
```

The Docker image bakes in the PyTorch toolkit and checkpoint so the runtime does
not need to create a virtualenv or install Python dependencies on your Mac.

For a lightweight connectivity test without PyTorch, run the placeholder engine:

```bash
ENGINE=placeholder scripts/run-handwriting-server-docker.sh
```

## Native Python Runner

Use this path when you want faster iteration on `scripts/handwriting-server.py`
without rebuilding a Docker image:

```bash
PORT=8878 scripts/run-handwriting-model-server.sh
```

The script clones the upstream handwriting toolkit into
`~/.cache/inka-handwriting/pytorch-handwriting-synthesis-toolkit`, creates a
virtualenv in `~/.cache/inka-handwriting/venv`, installs dependencies, and runs
`scripts/handwriting-server.py` with the toolkit `checkpoints/Epoch_52` model.

## Find Your Server URL

On macOS, find your Wi-Fi IP address:

```bash
ipconfig getifaddr en0
```

If that prints `192.168.1.25`, use this URL in Inka:

```text
http://192.168.1.25:8878
```

If USB debugging is connected, you can also reverse the port for local testing:

```bash
adb reverse tcp:8878 tcp:8878
```

Then use:

```text
http://127.0.0.1:8878
```

## Configure Inka

On the BOOX tablet:

```text
Settings -> Developer -> Experimental handwriting -> Hosted synthesis
Settings -> Developer -> Server Endpoint -> http://<server-ip>:8878
```

In debug builds, `Settings -> Developer -> Handwriting Lab -> Open` opens the
lab. Press `Run` to send the sample text to the selected synthesis engine.

The lab sends sample text to the selected engine, renders the returned strokes,
shows live status on screen, and logs generation/render timing to logcat with
the `HandwritingLab` tag. Its default sample uses seed `111111`, which has been
the best balance of quality and latency in local smoke tests.

## Verify The Server

From another terminal:

```bash
curl -s http://127.0.0.1:8878/health
```

Send a sample handwriting request:

```bash
curl -s http://127.0.0.1:8878/handwriting \
  -H 'content-type: application/json' \
  -d '{
    "text":"a little note for today",
    "pageWidth":1404,
    "pageHeight":1872,
    "left":96,
    "top":180,
    "maxWidth":1212,
    "fontSizeSp":40,
    "strokeWidthMm":0.3,
    "style":"lab-111111",
    "seed":111111
  }'
```

The response should include a `strokes` array. If it is empty, retry with the
real PyTorch engine and confirm the checkpoint finished loading.

## Seed And Quality Notes

The upstream model samples stochastically. It does not provide a canonical
"best seed"; the seed only makes the generated writer primer reproducible for
one server session.

Local smoke-test results:

- `111111`: best default balance; readable and usually under five seconds for
  short lab-style text.
- `555555`: fastest usable seed in the small benchmark, but a little less
  natural.
- `333333` and `888888`: can look okay but are slower and less stable on longer
  text.
- `24680`: avoid; it repeatedly produced collapsed, slow, hard-to-read samples.

Use the lab's `Random` button for exploration, but save only seeds that are both
readable and fast across more than one sentence.

## Cloudflare Deployment

Plain Cloudflare Workers are not a good runtime for the PyTorch handwriting
model. The Cloudflare path is a Worker that routes requests to a Cloudflare
Container running the same Dockerized Python server.

Scaffold:

```bash
cd deploy/cloudflare/handwriting-worker
npm install
npm run deploy
```

After deployment, set Inka:

```text
Settings -> Developer -> Experimental handwriting -> Hosted synthesis
Settings -> Developer -> Server Endpoint -> https://<your-worker>.workers.dev
```

Do not expose this as a public production endpoint until the app and Worker have
an auth token or another abuse-control mechanism. The current app server
contract sends reply text but no endpoint credential.

## Troubleshooting

- If the lab appears idle, check the status line above the controls. It should
  say `Ready`, `Sending to hosted endpoint`, `Generated`, `Rendering`, or
  `Rendered`.
- If `Run` stays at `Sending to hosted endpoint`, test health from the Mac:
  `curl http://127.0.0.1:8878/health`.
- If USB testing is enabled, confirm the reverse is active:
  `adb reverse --list`.
- If Inka says the request failed, confirm the BOOX tablet and server machine
  are on the same network and that the URL includes `http://`.
- If the server returns `strokes=[]`, try seed `111111` or another known-good
  seed. Some random seeds produce collapsed samples.
- If the Docker server starts slowly the first time, wait for image build and
  checkpoint loading to finish.
- If the generated handwriting is messy, retry with the same server after it is
  warm or use the lab seed controls to test another writer seed.
- If a firewall blocks the request, allow inbound connections to the selected
  port or use `adb reverse` while the tablet is connected over USB.
- Docker logs include generation timing and stroke/point counts:
  `docker compose logs --tail=50 handwriting-server`.

## License

The Docker image downloads and runs
`X-rayLaser/pytorch-handwriting-synthesis-toolkit`, which is MIT licensed. Keep
the upstream license notice when distributing a server image or hosted version.
