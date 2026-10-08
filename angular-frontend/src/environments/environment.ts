/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

// Relative URL: dev requests go through the ng serve proxy (proxy.conf.json) so the
// OIDC session cookie and the XSRF cookie stay on the same origin as the SPA.
export const environment = {
  production: false,
  apiUrl: '/api'
};
