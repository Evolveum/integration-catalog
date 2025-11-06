export interface ApplicationTag {
  id: number;
  name: string;
  displayName: string;
  tagType: string | null;
}

export interface CountryOfOrigin {
  id: number;
  name: string;
  displayName: string;
}

export interface Application {
  id: string;
  displayName: string;
  description: string;
  logo: string;
  riskLevel: string | null;
  lifecycleState: string | null;
  origins: CountryOfOrigin[] | null;
  categories: ApplicationTag[] | null;
  tags: ApplicationTag[] | null;
  pendingRequest?: boolean;
  requestId?: number | null;
  voteCount?: number;
}
