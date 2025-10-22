export interface ApplicationTag {
  id: number;
  name: string;
  displayName: string;
  tagType: string | null;
}

export interface Application {
  id: string;
  displayName: string;
  description: string;
  logo: string;
  riskLevel: string | null;
  lifecycleState: string | null;
  categories: ApplicationTag[] | null;
  tags: ApplicationTag[] | null;
  pendingRequest?: boolean;
}
