import { Routes } from '@angular/router';
import { ApplicationsList } from './components/applications-list/applications-list';
import { ApplicationDetail } from './application-detail/application-detail';

export const routes: Routes = [
  { path: '', redirectTo: '/applications', pathMatch: 'full' },
  { path: 'applications', component: ApplicationsList },
  { path: 'applications/:id', component: ApplicationDetail }
];
