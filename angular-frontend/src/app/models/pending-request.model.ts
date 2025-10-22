export interface PendingRequest {
  integrationApplicationName: string;
  baseUrl?: string;
  capabilities: string[];
  description: string;
  systemVersion?: string;
  email?: string;
  collab: boolean;
  requester?: string;
}
