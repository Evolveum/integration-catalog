/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

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
  requestId?: number | null;
  voteCount?: number;
}
