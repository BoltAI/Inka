import { Container } from "@cloudflare/containers";

type ContainerBinding = {
  getByName(name: string): {
    fetch(request: Request): Promise<Response>;
  };
};

interface Env {
  HANDWRITING_CONTAINER: ContainerBinding;
}

export class HandwritingContainer extends Container {
  defaultPort = 8787;
  sleepAfter = "30m";
  envVars = {
    HOST: "0.0.0.0",
    PORT: "8787",
    ENGINE: "pytorch",
    BIAS: "0.8",
    STEPS: "1500",
    DEVICE: "cpu",
    SAMPLE_ATTEMPTS: "8",
  };
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (request.method === "OPTIONS") {
      return withCors(new Response(null, { status: 204 }));
    }

    if (url.pathname !== "/health" && url.pathname !== "/handwriting") {
      return json({ error: "Not found" }, 404);
    }

    if (url.pathname === "/handwriting" && request.method !== "POST") {
      return json({ error: "Method not allowed" }, 405);
    }

    if (url.pathname === "/health" && request.method !== "GET") {
      return json({ error: "Method not allowed" }, 405);
    }

    const container = env.HANDWRITING_CONTAINER.getByName("default");
    const response = await container.fetch(request);
    return withCors(response);
  },
};

function json(body: unknown, status: number): Response {
  return withCors(
    new Response(JSON.stringify(body), {
      status,
      headers: {
        "content-type": "application/json",
      },
    }),
  );
}

function withCors(response: Response): Response {
  const headers = new Headers(response.headers);
  headers.set("access-control-allow-origin", "*");
  headers.set("access-control-allow-methods", "GET,POST,OPTIONS");
  headers.set("access-control-allow-headers", "content-type");
  return new Response(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers,
  });
}
