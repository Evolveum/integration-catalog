export interface ApplicationDetail {
  id: string;
  display_name: string;
  description: string;
  logo: string;
  risk_level: string | null;
  lifecycle_state: string;
  last_modified: string;
}
