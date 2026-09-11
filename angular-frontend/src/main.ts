/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { bootstrapApplication } from '@angular/platform-browser';
import { inject, provideAppInitializer } from '@angular/core';
import { AppComponent } from './app/app.component';
import { provideHttpClient, withXsrfConfiguration } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { routes } from './app/app.routes';
import { AuthService } from './app/services/auth.service';

// Identity travels in the backend session cookie (OIDC login); the XSRF cookie is mirrored into
// a header for mutating calls. Both names are the catalog's own: cookies ignore the port, so the
// default XSRF-TOKEN is shared with every other application on localhost (see SecurityConfig).
bootstrapApplication(AppComponent, {
  providers: [
    provideRouter(routes),
    provideHttpClient(withXsrfConfiguration({
      cookieName: 'IC-XSRF-TOKEN',
      headerName: 'X-IC-XSRF-TOKEN'
    })),
    provideAppInitializer(() => inject(AuthService).loadCurrentUser())
  ]
}).catch(err => console.error(err));
