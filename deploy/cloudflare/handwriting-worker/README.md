# Inka Handwriting Cloudflare Worker

This Worker routes `/health` and `/handwriting` to the same Dockerized Python
handwriting server used for local Mac hosting.

Plain Cloudflare Workers are not the runtime for the PyTorch model. This
project uses Cloudflare Containers, with the Worker acting as the public HTTP
router.

## Deploy

Prerequisites:

- Docker is running locally.
- A Cloudflare account with Workers paid plan access to Containers.
- `npm` is available.

```bash
cd deploy/cloudflare/handwriting-worker
npm install
npm run deploy
```

After deployment, wait for the container to become ready, then test:

```bash
curl https://<your-worker>.workers.dev/health
```

Set Inka:

```text
Settings -> Developer -> Experimental handwriting -> Hosted synthesis
Settings -> Developer -> Server Endpoint -> https://<your-worker>.workers.dev
```

## Security

This scaffold is intentionally minimal and unauthenticated because the current
Inka app does not send a handwriting-server auth header. Do not expose it as a
public production endpoint until the app and Worker support an API token or
another abuse-control mechanism.
