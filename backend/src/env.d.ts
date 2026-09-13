/// <reference types="@cloudflare/workers-types" />

declare namespace Cloudflare {
  interface Env {
    TRIPS: DurableObjectNamespace;
  }
}

interface Env {
  TRIPS: DurableObjectNamespace;
}
