/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

export interface ImplementationListItem {
  id: string;
  name: string;
  description: string;
  publishedDate: string;
  version: string;
  displayName: string;
  maintainer: string;
  licenseType: string;
  implementationDescription: string;
  browseLink: string;
  ticketingLink: string;
  buildFramework: string;
  checkoutLink: string;
  pathToProjectDirectory: string;
}
