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
import { ApprovalQueuePage } from './components/approval-queue-page/approval-queue-page';
import { ErrorPage, errorPageTitle } from './components/error-page/error-page';

// title: tab title, suffixed with the catalog name by PageTitleService (detail screens replace it with the loaded name).
// data.hideFooter: forms with their own fixed/scrolling layout (publish, edit incl. add connector) have no page footer.
export const routes: Routes = [
  { path: '', redirectTo: '/applications', pathMatch: 'full' },
  { path: 'applications', component: ApplicationsList, title: 'Applications' },
  { path: 'applications/:id', component: ApplicationDetail, title: 'Application' },
  { path: 'applications/:appId/integration-method/:versionId/:revision/details', component: IntegrationMethodDetail, title: 'Integration method' },
  { path: 'applications/:appId/integration-method/:versionId/:revision/edit', component: EditUpgradeForm, title: 'Edit integration method', data: { hideFooter: true } },
  { path: 'approve', component: PublishFormMain, title: 'Publish integration method', data: { hideFooter: true } },
  { path: 'settings', component: SettingsPage, title: 'Settings' },
  { path: 'my-integration-methods', component: MyIntegrationMethodsPage, title: 'My integration methods' },
  { path: 'all-integration-methods', component: MyIntegrationMethodsPage, title: 'All integration methods', data: { scope: 'all' } },
  { path: 'my-connectors', component: MyConnectorsPage, title: 'My connectors' },
  { path: 'all-connectors', component: MyConnectorsPage, title: 'All connectors', data: { scope: 'all' } },
  { path: 'approval-queue', component: ApprovalQueuePage, title: 'Approval queue' },
  { path: 'support-tiers', component: SupportTiersPage, title: 'Integration methods by support tier' },
  { path: 'error-page/:status', component: ErrorPage, title: errorPageTitle },
  { path: '**', component: ErrorPage, title: errorPageTitle }
];
