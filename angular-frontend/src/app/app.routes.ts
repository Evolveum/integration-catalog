import { Routes } from '@angular/router';
import { HomeComponent } from './features/homepage/homepage.component';

export const routes: Routes = [
    { path: '', component: HomeComponent },
    { path: '**', redirectTo: '' }
  ];
