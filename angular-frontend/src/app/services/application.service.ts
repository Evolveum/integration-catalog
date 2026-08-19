/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Injectable } from '@angular/core';
import {HttpClient, HttpErrorResponse, HttpParams, HttpResponse} from '@angular/common/http';
import {catchError, from, mergeMap, Observable, of} from 'rxjs';
import { map } from 'rxjs/operators';
import { Application } from '../models/application.model';
import { MidpointVersion } from '../models/application-detail.model';
import { ApplicationDetail, ApplicationTag } from '../models/application-detail.model';
import { CategoryCount } from '../models/category-count.model';
import { ImplementationListItem } from '../models/implementation-list-item.model';
import { CatalogConnector } from '../models/catalog-connector.model';
import { IntegrationRequest, UploadConnectorPayload } from '../models/request.model';
import { environment } from '../../environments/environment';
import { ProblemDetail } from '../models/problem-detail';
import { AuthService } from './auth.service';

/** Outcome of a bundle download: data for the post-download help modal + optional server warning. */
export interface BundleDownloadResult {
  warning: string | null;
  fileName: string;
  size: number | null;
}

/** Outcome of an active-connectors sheet download: help-modal data, or an error message. */
export interface SheetDownloadResult {
  error: string | null;
  fileName: string | null;
  size: number | null;
}

/**
 * The support-portal work package behind a revision's review (GET .../support-ticket).
 * `approvalReady` is the backend's verdict — it compares the status against the one configured
 * as the go-ahead, so the status name never has to be known here.
 */
export interface SupportTicket {
  configured: boolean;
  ticketId: number | null;
  url: string | null;
  status: string | null;
  approvalReady: boolean;
  error: string | null;
}

@Injectable({
  providedIn: 'root'
})
export class ApplicationService {
  private readonly apiUrl = `${environment.apiUrl}/applications`;

  constructor(private http: HttpClient, private authService: AuthService) {}

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

  /**
   * Whether another connector with the same identity (bundle name + class name) already carries
   * this version. Informational only — duplicates are never blocked; the reviewer decides whether
   * the existing version should be reused.
   */
  checkVersionExists(bundleName: string, className: string | null, version: string,
                     excludeConnectorId?: number | null): Observable<boolean> {
    let params = new HttpParams().set('bundleName', bundleName).set('version', version);
    if (className) params = params.set('className', className);
    if (excludeConnectorId != null) params = params.set('excludeConnectorId', excludeConnectorId);
    return this.http.get<boolean>(`${environment.apiUrl}/upload/check-version`, { params });
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

  uploadTutorialFile(appId: string, methodId: string, revision: string, file: File): Observable<void> {
    const formData = new FormData();
    formData.append('file', file);
    return this.http.post<void>(
      `${environment.apiUrl}/applications/${appId}/integration-method/${methodId}/${encodeURIComponent(revision)}/tutorial`,
      formData
    );
  }

  listTutorialFiles(appId: string, methodId: string, revision: string): Observable<string[]> {
    return this.http.get<string[]>(
      `${environment.apiUrl}/applications/${appId}/integration-method/${methodId}/${encodeURIComponent(revision)}/tutorial`
    );
  }

  deleteTutorialFile(appId: string, methodId: string, revision: string, name: string): Observable<void> {
    return this.http.delete<void>(
      `${environment.apiUrl}/applications/${appId}/integration-method/${methodId}/${encodeURIComponent(revision)}/tutorial/file?name=${encodeURIComponent(name)}`
    );
  }

  getTutorialFileUrl(appId: string, methodId: string, revision: string, name: string): string {
    return `${environment.apiUrl}/applications/${appId}/integration-method/${methodId}/${encodeURIComponent(revision)}/tutorial/file?name=${encodeURIComponent(name)}`;
  }

  /**
   * Downloads the integration-method bundle ZIP and triggers a browser save.
   * Emits the saved file name and size (for the post-download help modal) plus the value of the
   * `X-Bundle-Warning` response header (or `null`) so callers can notify the user when the
   * connector build file could not be included.
   */
  downloadBundle(appId: string, methodId: string, revision: string): Observable<BundleDownloadResult> {
    const url = `${environment.apiUrl}/applications/${appId}/integration-method/${methodId}/${encodeURIComponent(revision)}/bundle`;
    return this.http.get(url, { observe: 'response', responseType: 'blob' }).pipe(
      map((response: HttpResponse<Blob>) => {
        const fileName = this.parseContentDispositionFileName(response.headers.get('Content-Disposition')) ?? 'bundle.zip';
        if (response.body) {
          this.saveBlob(response.body, fileName);
        }
        return {
          warning: response.headers.get('X-Bundle-Warning'),
          fileName,
          size: response.body?.size ?? null
        };
      })
    );
  }

  /**
   * Downloads the active-connectors sheet (JSON) and triggers a browser save.
   * Emits the saved file name and size (for the post-download help modal), or an error
   * message when the download failed.
   */
  downloadActiveConnectors(): Observable<SheetDownloadResult> {
    const url = `${environment.apiUrl}/connectors/active`;
    return this.http.get(url, { observe: 'response', responseType: 'blob' }).pipe(
      map((response: HttpResponse<Blob>) => {
        const fileName = this.parseContentDispositionFileName(response.headers.get('Content-Disposition')) ?? 'active-connectors.json';
        if (response.body) {
          this.saveBlob(response.body, fileName);
        }
        return {
          error: null,
          fileName,
          size: response.body?.size ?? null
        };
      }),
      catchError((error: HttpErrorResponse) => {
        if (error.error instanceof Blob) {
          return from(error.error.text()).pipe(
            map(text => {
              const problem = JSON.parse(text) as ProblemDetail;
              return { error: problem.detail ?? 'Download of active connectors failed.', fileName: null, size: null };
            })
          );
        }

        return of({ error: 'Download of active connectors failed.', fileName: null, size: null });
      })
    );
  }

  private saveBlob(blob: Blob, fileName: string): void {
    const objectUrl = window.URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = objectUrl;
    anchor.download = fileName;
    document.body.appendChild(anchor);
    anchor.click();
    document.body.removeChild(anchor);
    window.URL.revokeObjectURL(objectUrl);
  }

  private parseContentDispositionFileName(disposition: string | null): string | null {
    if (!disposition) {
      return null;
    }
    const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposition);
    return match ? decodeURIComponent(match[1]) : null;
  }

  editIntegrationMethod(
    appId: string,
    methodId: string,
    currentRevision: string,
    payload: { displayName: string; description: string; tutorial: string; capabilities: { objectClass: string; capabilityNames: string[] }[]; removeFile: boolean; minorBump: boolean; midpointMinVersion: number | null; midpointMaxVersion: number | null }
  ): Observable<string> {
    return this.http.put<string>(
      `${environment.apiUrl}/applications/${appId}/integration-method/${methodId}/${encodeURIComponent(currentRevision)}`,
      payload,
      { responseType: 'text' as 'json' }
    );
  }

  /**
   * State of the support-portal work package opened when this revision was submitted.
   * The backend restricts this to the submitting side and the reviewer, and identifies the
   * caller by the username sent along - the same way the other user-scoped endpoints do.
   */
  getSupportTicket(appId: string, methodId: string, revision: string): Observable<SupportTicket> {
    return this.http.get<SupportTicket>(
      `${environment.apiUrl}/applications/${appId}/integration-method/${methodId}/${encodeURIComponent(revision)}/support-ticket`,
      { params: new HttpParams().set('username', this.authService.currentUser() ?? '') }
    );
  }

  startReviewIntegrationMethod(appId: string, methodId: string, revision: string): Observable<void> {
    return this.http.post<void>(
      `${environment.apiUrl}/applications/${appId}/integration-method/${methodId}/${encodeURIComponent(revision)}/start-review`,
      {}
    );
  }

  stopReviewIntegrationMethod(appId: string, methodId: string, revision: string): Observable<void> {
    return this.http.post<void>(
      `${environment.apiUrl}/applications/${appId}/integration-method/${methodId}/${encodeURIComponent(revision)}/stop-review`,
      {}
    );
  }

  publishIntegrationMethod(appId: string, methodId: string, revision: string): Observable<void> {
    return this.http.post<void>(
      `${environment.apiUrl}/applications/${appId}/integration-method/${methodId}/${encodeURIComponent(revision)}/publish`,
      {}
    );
  }

  rejectIntegrationMethod(appId: string, methodId: string, revision: string): Observable<void> {
    return this.http.post<void>(
      `${environment.apiUrl}/applications/${appId}/integration-method/${methodId}/${encodeURIComponent(revision)}/reject`,
      {}
    );
  }

  getConnectorsForIntegrationMethod(appId: string, methodId: string, revision: string): Observable<ImplementationListItem[]> {
    return this.http.get<ImplementationListItem[]>(
      `${environment.apiUrl}/applications/${appId}/integration-method/${methodId}/${encodeURIComponent(revision)}/connectors`
    );
  }

  addConnectorToIntegrationMethod(
    appId: string,
    versionId: string,
    revision: string,
    payload: {
      existingConnectorId: number | null;
      displayName: string; description: string; maintainer: string;
      framework: string; license: string | null;
      browseLink: string | null; gitCloneUrl: string | null;
      buildFramework: string | null; pathToProject: string | null;
      className: string | null; bundleDisplayName: string | null;
      version: string | null; commitTag: string | null;
      midpointMinVersion: number | null; midpointMaxVersion: number | null;
      connectorVersionFrom: string | null; connectorVersionTo: string | null;
      connectorCapabilities: { objectClass: string; capabilityNames: string[] }[];
    }
  ): Observable<string> {
    // Returns the revision the connector was added to: the current revision, or a newly forked draft
    // revision when the source was a published version.
    return this.http.post(
      `${environment.apiUrl}/applications/${appId}/integration-method/${versionId}/${encodeURIComponent(revision)}/connectors`,
      payload,
      { responseType: 'text' }
    );
  }

  updateConnector(
    appId: string,
    methodId: string,
    revision: string,
    connectorId: number,
    payload: {
      displayName: string; description: string; maintainer: string;
      license: string | null; browseLink: string | null; supportPortal: string | null;
      gitCloneUrl: string | null; buildFramework: string | null;
      pathToProject: string | null; className: string | null; bundleDisplayName: string | null;
      commitTag: string | null; version: string | null;
      connectorCapabilities: { objectClass: string; capabilityNames: string[] }[];
    }
  ): Observable<void> {
    return this.http.put<void>(
      `${environment.apiUrl}/applications/${appId}/integration-method/${methodId}/${encodeURIComponent(revision)}/connectors/${connectorId}`,
      payload
    );
  }

  deleteConnector(appId: string, methodId: string, revision: string, connectorId: number): Observable<void> {
    return this.http.delete<void>(
      `${environment.apiUrl}/applications/${appId}/integration-method/${methodId}/${encodeURIComponent(revision)}/connectors/${connectorId}`
    );
  }

  updateConnectorCompatibility(
    appId: string,
    methodId: string,
    revision: string,
    connectorId: number,
    payload: { connectorVersionFrom: string | null; connectorVersionTo: string | null }
  ): Observable<void> {
    return this.http.put<void>(
      `${environment.apiUrl}/applications/${appId}/integration-method/${methodId}/${encodeURIComponent(revision)}/connectors/${connectorId}/compatibility`,
      payload
    );
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
