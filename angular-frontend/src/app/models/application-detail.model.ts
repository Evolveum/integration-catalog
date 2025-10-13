export interface CountryOfOrigin {
  id: number;
  name: string;
  displayName: string;
}

export interface ApplicationTag {
  id: number;
  name: string;
  displayName: string;
  tagType: string | null;
}

export interface ImplementationVersion {
  description: string | null;
  implementationTags: string[] | null;
  capabilities: string[] | null;
  connectorVersion: string | null;
  systemVersion: string | null;
  releasedDate: string | null;
  author: string | null;
  lifecycleState: string | null;
  downloadLink: string | null;
}

export interface ApplicationDetail {
  id: string;
  displayName: string;
  description: string;
  logo: string;
  riskLevel: string | null;
  lifecycleState: string;
  lastModified: string;
  origins: CountryOfOrigin[] | null;
  categories: ApplicationTag[] | null;
  tags: ApplicationTag[] | null;
  implementationVersions: ImplementationVersion[] | null;
}
