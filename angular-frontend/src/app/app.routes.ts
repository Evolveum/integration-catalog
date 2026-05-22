/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Routes } from '@angular/router';
import { ApplicationsList } from './components/applications-list/applications-list';
import { ApplicationDetail } from './components/application-detail/application-detail';
import { PublishFormMain } from './components/publish-form-main/publish-form-main';
import { EditUpgradeForm } from './components/edit-upgrade-form/edit-upgrade-form';

export const routes: Routes = [
  { path: '', redirectTo: '/applications', pathMatch: 'full' },
  { path: 'applications', component: ApplicationsList },
  { path: 'applications/:id', component: ApplicationDetail },
  { path: 'applications/:appId/integration-method/:versionId/edit', component: EditUpgradeForm },
  { path: 'publish', component: PublishFormMain }
];
