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
import { IntegrationMethodDetail } from './components/integration-method-detail/integration-method-detail';
import { SettingsPage } from './components/settings-page/settings-page';
import { SupportTiersPage } from './components/support-tiers-page/support-tiers-page';
import { MyIntegrationMethodsPage } from './components/my-integration-methods-page/my-integration-methods-page';
import { MyConnectorsPage } from './components/my-connectors-page/my-connectors-page';

export const routes: Routes = [
  { path: '', redirectTo: '/applications', pathMatch: 'full' },
  { path: 'applications', component: ApplicationsList },
  { path: 'applications/:id', component: ApplicationDetail },
  { path: 'applications/:appId/integration-method/:versionId/:revision/details', component: IntegrationMethodDetail },
  { path: 'applications/:appId/integration-method/:versionId/:revision/edit', component: EditUpgradeForm },
  { path: 'approve', component: PublishFormMain },
  { path: 'settings', component: SettingsPage },
  { path: 'my-integration-methods', component: MyIntegrationMethodsPage },
  { path: 'my-connectors', component: MyConnectorsPage },
  { path: 'support-tiers', component: SupportTiersPage }
];
