/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Application } from '../models/application.model';
import { ApplicationDetail } from '../models/application-detail.model';
import { CategoryCount } from '../models/category-count.model';
import { environment } from '../../environments/environment';

@Injectable({
  providedIn: 'root'
})
export class ApplicationService {
  private readonly apiUrl = `${environment.apiUrl}/applications`;

  constructor(private http: HttpClient) {}

  getAll(): Observable<Application[]> {
    return this.http.get<Application[]>(this.apiUrl);
  }

  getById(id: string): Observable<ApplicationDetail> {
    return this.http.get<ApplicationDetail>(`${environment.apiUrl}/applications/${id}`);
  }

  getCategoryCounts(): Observable<CategoryCount[]> {
    return this.http.get<CategoryCount[]>(`${environment.apiUrl}/categories/counts`);
  }

  submitVote(requestId: number, voter: string): Observable<any> {
    return this.http.post<any>(`${environment.apiUrl}/requests/${requestId}/vote?voter=${voter}`, {});
  }

  getVoteCount(requestId: number): Observable<number> {
    return this.http.get<number>(`${environment.apiUrl}/requests/${requestId}/votes/count`);
  }

  hasUserVoted(requestId: number, voter: string): Observable<boolean> {
    return this.http.get<boolean>(`${environment.apiUrl}/requests/${requestId}/votes/check?voter=${voter}`);
  }

  submitRequest(request: any): Observable<any> {
    return this.http.post<any>(`${environment.apiUrl}/requests`, request);
  }
}
