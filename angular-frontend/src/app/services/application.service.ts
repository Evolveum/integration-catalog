/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Application } from '../models/application.model';
import { MidpointVersion } from '../models/application-detail.model';
import { ApplicationDetail, ApplicationTag } from '../models/application-detail.model';
import { CategoryCount } from '../models/category-count.model';
import { ImplementationListItem } from '../models/implementation-list-item.model';
import { CatalogConnector } from '../models/catalog-connector.model';
import { IntegrationRequest, UploadConnectorPayload } from '../models/request.model';
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

  submitVote(requestId: number, voter: string): Observable<void> {
    return this.http.post<void>(`${environment.apiUrl}/requests/${requestId}/vote?voter=${voter}`, {});
  }

  getVoteCount(requestId: number): Observable<number> {
    return this.http.get<number>(`${environment.apiUrl}/requests/${requestId}/votes/count`);
  }

  hasUserVoted(requestId: number, voter: string): Observable<boolean> {
    return this.http.get<boolean>(`${environment.apiUrl}/requests/${requestId}/votes/check?voter=${voter}`);
  }

  submitRequest(request: IntegrationRequest): Observable<void> {
    return this.http.post<void>(`${environment.apiUrl}/requests`, request);
  }

  getCapabilities(): Observable<{ name: string; globality: string; displayOrder: number | null }[]> {
    return this.http.get<{ name: string; globality: string; displayOrder: number | null }[]>(`${environment.apiUrl}/capabilities`);
  }

  getImplementationsByApplicationId(applicationId: string): Observable<ImplementationListItem[]> {
    return this.http.get<ImplementationListItem[]>(`${environment.apiUrl}/applications/${applicationId}/implementations`);
  }

  getCatalogConnectors(): Observable<CatalogConnector[]> {
    return this.http.get<CatalogConnector[]>(`${environment.apiUrl}/connectors/catalog`);
  }

  uploadConnector(payload: UploadConnectorPayload): Observable<string> {
    return this.http.post<string>(`${environment.apiUrl}/upload/connector`, payload, { responseType: 'text' as 'json' });
  }

  checkVersionExists(version: string): Observable<boolean> {
    return this.http.get<boolean>(`${environment.apiUrl}/upload/check-version?version=${encodeURIComponent(version)}`);
  }

  checkBundleNameExists(bundleName: string): Observable<boolean> {
    return this.http.get<boolean>(`${environment.apiUrl}/upload/check-bundle-name?bundleName=${encodeURIComponent(bundleName)}`);
  }

  getAllTags(): Observable<ApplicationTag[]> {
    return this.http.get<ApplicationTag[]>(`${environment.apiUrl}/application-tags`);
  }

  getIntegrationMethodTypes(): Observable<{ id: number; displayName: string; description: string | null }[]> {
    return this.http.get<{ id: number; displayName: string; description: string | null }[]>(`${environment.apiUrl}/integration-method-types`);
  }

  getMidpointVersions(): Observable<MidpointVersion[]> {
    return this.http.get<MidpointVersion[]>(`${environment.apiUrl}/midpoint-versions`);
  }

  getTotalDownloadsCount(): Observable<number> {
    return this.http.get<number>(`${environment.apiUrl}/statistics/downloads-count`);
  }

  getApplicationDownloadsCount(applicationId: string): Observable<number> {
    return this.http.get<number>(`${environment.apiUrl}/applications/${applicationId}/downloads-count`);
  }

  downloadConnector(versionId: string): void {
    const url = `${environment.apiUrl}/downloads/${versionId}`;
    window.open(url, '_blank');
  }

  // ==================== Logo Methods ====================

  /**
   * Get the logo URL for an application
   */
  getLogoUrl(applicationId: string): string {
    return `${environment.apiUrl}/applications/${applicationId}/logo`;
  }

  /**
   * Upload a logo for an application
   */
  uploadLogo(applicationId: string, file: File): Observable<void> {
    const formData = new FormData();
    formData.append('file', file);
    return this.http.post<void>(
      `${environment.apiUrl}/applications/${applicationId}/logo`,
      formData
    );
  }

  uploadTutorial(integrationMethodId: string, file: File): Observable<void> {
    const formData = new FormData();
    formData.append('file', file);
    return this.http.post<void>(
      `${environment.apiUrl}/integration-methods/${integrationMethodId}/tutorial`,
      formData
    );
  }

  /**
   * Delete a logo for an application
   */
  deleteLogo(applicationId: string): Observable<void> {
    return this.http.delete<void>(
      `${environment.apiUrl}/applications/${applicationId}/logo`
    );
  }

  cancelRequest(requestId: number): Observable<void> {
    return this.http.delete<void>(`${environment.apiUrl}/requests/${requestId}`);
  }

  // ==================== Recently Used ====================

  getRecentlyUsed(): Observable<Application[]> {
    return this.http.get<Application[]>(`${environment.apiUrl}/recently-used`);
  }

  recordRecentlyUsed(applicationId: string, username: string): Observable<void> {
    return this.http.post<void>(
      `${environment.apiUrl}/recently-used/${applicationId}`,
      {},
      { headers: { 'X-User-Name': username } }
    );
  }
}
