export interface Application {
  id: string;
  displayName: string;
  description: string;
  logo: string;
  riskLevel: string | null;
  lifecycleState: string | null;
}
