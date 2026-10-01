/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

/** GET /api/auth/me/items: what the current user maintains, personally or through their organization. */
export interface MyItems {
  /** Every revision, newest first within one method. */
  integrationMethods: MyIntegrationMethod[];
  connectors: MyConnector[];
}

export interface MyIntegrationMethod {
  applicationId: string;
  applicationDisplayName: string | null;
  applicationHasLogo: boolean;
  id: string;
  revision: string;
  displayName: string | null;
  lifecycleState: string | null;
  /** The original submission date, shared by all revisions. */
  createdAt: string | null;
  /** Last change; the review start date while REVIEWING. */
  updated: string | null;
}

export interface MyConnector {
  id: number;
  displayName: string | null;
  version: string | null;
  /** ACTIVE once any of its versions is published, else the newest version's state. */
  lifecycleState: string | null;
  usedBy: MyConnectorUsage[];
}

/** A method linking a connector; the revision is its published one when it has one. */
export interface MyConnectorUsage {
  applicationId: string;
  integrationMethodId: string;
  revision: string;
  displayName: string | null;
}
