import { Routes } from '@angular/router';
import { ApplicationsListComponent } from './components/applications-list/applications-list.component';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'applications' },
  { path: 'applications', component: ApplicationsListComponent },
];
