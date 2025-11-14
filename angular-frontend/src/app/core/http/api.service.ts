/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';

export interface ApplicationCard {
  id: string;
  name: string;
  vendor?: string;
  shortDescription?: string;
  logoUrl?: string;
  featured?: boolean;
  rating?: number | null;
  tags?: string[];
}

@Injectable({ providedIn: 'root' })
export class ApiService {
  private http = inject(HttpClient);
  private base = '/api';

  getApplications(params?: any) {
    return this.http.get(`${this.base}/applications`, { params });
  }
}
