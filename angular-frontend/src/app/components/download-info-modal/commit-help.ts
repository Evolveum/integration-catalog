/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { DownloadInfoStep } from './download-info-modal';

// Content of the "How to find it" help next to the commit hash field (publish flow and the
// edit & upgrade flow's add-connector form).
export const COMMIT_HELP_TITLE = 'How to specify the commit hash';

export const COMMIT_HELP_STEPS: DownloadInfoStep[] = [
  {
    title: 'Open the connector repository',
    description: 'Open the Git repository containing the connector source code for which you want to create a new connector version.'
  },
  {
    title: 'Select the required tag or release',
    description: 'Navigate to the repository tags or releases and select the desired connector version.'
  },
  {
    title: 'Open the commit history',
    description: 'Open the commit history and locate the commit that represents the connector version. ' +
    ' In most cases, this will be the latest commit associated with the tag. '
  },
  {
    title: 'Copy the commit hash',
    description: 'Open the selected commit details and copy the commit hash value. This hash uniquely ' +
    ' identifies the exact source code version of the connector. '
  },
  {
    title: 'Paste the commit hash',
    description: 'Paste the copied commit hash into the *Commit hash* field in the Integration Catalog.'
  }
];
