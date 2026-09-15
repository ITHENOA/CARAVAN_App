export interface Env {
  TRIPS: DurableObjectNamespace;
  INVITES: DurableObjectNamespace;
  ASSETS: Fetcher;
  /** Full Firebase service-account JSON string (wrangler secret / .dev.vars). */
  FCM_SERVICE_ACCOUNT_JSON?: string;
}
