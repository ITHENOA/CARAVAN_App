/// <reference types="@cloudflare/workers-types" />

declare namespace Cloudflare {
  interface Env {
    TRIPS: DurableObjectNamespace;
  }
}

interface Env {
  TRIPS: DurableObjectNamespace;
  INVITES: DurableObjectNamespace;
  ASSETS: Fetcher;
  FCM_SERVICE_ACCOUNT_JSON?: string;
}
