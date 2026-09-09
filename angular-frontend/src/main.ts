/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { bootstrapApplication } from '@angular/platform-browser';
import { inject, provideAppInitializer } from '@angular/core';
import { AppComponent } from './app/app.component';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { routes } from './app/app.routes';
import { AuthService } from './app/services/auth.service';

// Identity travels in the backend session cookie (OIDC login); Angular's default XSRF
// support mirrors the XSRF-TOKEN cookie into the X-XSRF-TOKEN header for mutating calls.
bootstrapApplication(AppComponent, {
  providers: [
    provideRouter(routes),
    provideHttpClient(),
    provideAppInitializer(() => inject(AuthService).loadCurrentUser())
  ]
}).catch(err => console.error(err));
