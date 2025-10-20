import { Routes } from '@angular/router';
import { ApplicationsList } from './components/applications-list/applications-list';
import { ApplicationDetail } from './components/application-detail/application-detail';
import { RequestForm } from './components/request-form/request-form';

export const routes: Routes = [
  { path: '', redirectTo: '/applications', pathMatch: 'full' },
  { path: 'applications', component: ApplicationsList },
  { path: 'applications/:id', component: ApplicationDetail },
  { path: 'request', component: RequestForm }
];
