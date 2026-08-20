export interface WebhookEndpointSummary {
  id: string;
  agentSlug: string;
  label: string;
  eventType: string;
  runAsUserId: string;
  deliveryUrl: string;
  createdAt: string;
}

export interface CreateWebhookEndpointRequest {
  agentSlug: string;
  label: string;
  /** Omitted (null) means "run as the ADMIN creating it" -- see CreateWebhookEndpointRequest's javadoc. */
  runAsUserId: string | null;
}

/**
 * secret is populated only on the create response -- every later read uses
 * WebhookEndpointSummary, which carries no secret field at all.
 */
export interface WebhookEndpointCreatedResponse {
  id: string;
  agentSlug: string;
  label: string;
  deliveryUrl: string;
  secret: string;
}
