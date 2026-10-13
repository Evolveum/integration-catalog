/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Maintainer } from './maintainer.model';
import { ConnectorTag } from './connector-tag.model';

/** GET /api/auth/me/integration-methods: one revision of a method the current user maintains. */
export interface MyIntegrationMethod {
  applicationId: string;
  applicationDisplayName: string | null;
  applicationHasLogo: boolean;
  id: string;
  revision: string;
  displayName: string | null;
  connectorDisplayName: string | null;
  lifecycleState: string | null;
  /** The original submission date, shared by all revisions. */
  createdAt: string | null;
  /** Last change; the review start date while REVIEWING. */
  updated: string | null;
  /** The same moment with its time, for how long a revision has waited. */
  updatedAt: string | null;
  author: string | null;
  supportTicketId: number | null;
  /** Null when the support portal is not configured. */
  supportTicketUrl: string | null;
  maintainer: Maintainer;
}

/** GET /api/auth/me/connectors: a connector the current user maintains. */
export interface MyConnector {
  id: number;
  displayName: string | null;
  maintainer: Maintainer;
  /** Sorted by display name; supp_tier_* ones hold the support tier. */
  tags: ConnectorTag[];
  /** Newest first. */
  versions: MyConnectorVersion[];
}

export interface MyConnectorVersion {
  /** The "Connector version" given when the connector was added. */
  version: string | null;
  author: string | null;
  uploaded: string | null;
  lifecycleState: string | null;
  usedBy: MyConnectorUsage[];
}

export interface MyConnectorUsage {
  applicationId: string;
  applicationDisplayName: string | null;
  integrationMethodId: string;
  revision: string;
  displayName: string | null;
}
