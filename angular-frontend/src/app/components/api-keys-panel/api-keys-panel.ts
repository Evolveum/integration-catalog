/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { ToastService } from '../../services/toast.service';
import { ApiKey, ApiKeyService } from '../../services/api-key.service';
import { CreateApiKeyModal, CreateApiKeyRequest } from '../create-api-key-modal/create-api-key-modal';
import { RevokeApiKeyModal } from '../revoke-api-key-modal/revoke-api-key-modal';

type ApiKeyStatus = 'Active' | 'Replaced' | 'Expired' | 'Revoked';

/**
 * The API keys tab of the settings page: the user's keys, the creation modal and the one-time
 * display of a new key.
 */
@Component({
  selector: 'app-api-keys-panel',
  standalone: true,
  imports: [CommonModule, CreateApiKeyModal, RevokeApiKeyModal],
  templateUrl: './api-keys-panel.html',
  styleUrls: ['./api-keys-panel.scss']
})
export class ApiKeysPanel implements OnInit {
  private readonly toastService = inject(ToastService);
  private readonly apiKeyService = inject(ApiKeyService);

  protected readonly keys = signal<ApiKey[]>([]);
  protected readonly loading = signal(true);
  /** True when the list could not be loaded, so an empty table is not read as "no keys". */
  protected readonly loadFailed = signal(false);
  protected readonly creating = signal(false);

  protected readonly activeKeys = computed(() => this.keys().filter(key => !this.isInactive(key)));
  protected readonly inactiveKeys = computed(() => this.keys().filter(key => this.isInactive(key)));

  protected readonly inactiveOpen = signal(false);

  protected readonly modalOpen = signal(false);

  /** The key the revoke dialog is asking about. */
  protected readonly revoking = signal<ApiKey | null>(null);
  protected readonly revokeInFlight = signal(false);

  /** The key the rotate dialog is asking about. */
  protected readonly rotating = signal<ApiKey | null>(null);
  protected readonly rotateInFlight = signal(false);

  /** Shown once: nothing can produce this value again. */
  protected readonly createdValue = signal<string | null>(null);
  protected readonly createdTitle = signal('API key created');
  protected readonly createdText = signal("Copy this key now. You won't be able to view it again after you finish.");
  protected readonly valueVisible = signal(false);

  protected readonly openMenuKeyId = signal<string | null>(null);

  ngOnInit(): void {
    this.load();
  }

  protected openCreate(): void {
    this.modalOpen.set(true);
  }

  /** The scoped variant additionally attaches catalog items to the key; that screen is next. */
  protected createWithScope(): void {
    this.toastService.show('Create key (with scope)', 'Scoped keys are not available yet.', 'info');
  }

  /** Creates the key and shows its value; the list is reloaded so it shows what was stored. */
  protected onCreate(request: CreateApiKeyRequest): void {
    this.creating.set(true);
    this.apiKeyService.create(request.name, request.expiresAt).subscribe({
      next: created => {
        this.creating.set(false);
        this.modalOpen.set(false);
        this.showValue(created.value, 'API key created',
          "Copy this key now. You won't be able to view it again after you finish.");
        this.load();
        if (!created.expirationAccepted) {
          this.toastService.show('API key created',
            'The key was created, but its expiration was refused — it does not expire.', 'warning');
        }
      },
      error: (error: HttpErrorResponse) => {
        this.creating.set(false);
        this.toastService.show('API key', this.messageOf(error), 'danger');
      }
    });
  }

  /** Hides the created-key notice; the value is unrecoverable afterwards. */
  protected dismissCreated(): void {
    this.createdValue.set(null);
    this.valueVisible.set(false);
  }

  protected toggleValue(): void {
    this.valueVisible.update(visible => !visible);
  }

  protected async copyValue(value: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(value);
      this.toastService.show('API key', 'The key was copied to the clipboard.', 'success');
    } catch {
      this.toastService.show('API key', 'The key could not be copied — copy it manually.', 'warning');
    }
  }

  protected readonly maskedValue = computed(() => '•'.repeat(this.createdValue()?.length ?? 0));

  protected toggleMenu(keyId: string): void {
    this.openMenuKeyId.update(open => (open === keyId ? null : keyId));
  }

  protected closeMenu(): void {
    this.openMenuKeyId.set(null);
  }

  protected rotate(key: ApiKey): void {
    this.closeMenu();
    this.rotating.set(key);
  }

  protected rotateText(key: ApiKey): string {
    return `A new key replaces "${key.name}". The current key keeps working for two more hours, `
      + 'so update everything that uses it before then.';
  }

  /** Replaces the key with a new one under the same name; the old one stays listed as replaced. */
  protected confirmRotate(): void {
    const key = this.rotating();
    if (!key) {
      return;
    }
    this.rotateInFlight.set(true);
    this.apiKeyService.renew(key.id).subscribe({
      next: renewed => {
        this.rotateInFlight.set(false);
        this.rotating.set(null);
        this.showValue(renewed.value, 'API key rotated',
          "Copy the new key now. You won't be able to view it again after you finish. "
          + 'The previous key keeps working for two hours.');
        this.load();
      },
      error: (error: HttpErrorResponse) => {
        this.rotateInFlight.set(false);
        this.toastService.show('API key', this.messageOf(error, 'The API key could not be rotated.'), 'danger');
      }
    });
  }

  protected revoke(key: ApiKey): void {
    this.closeMenu();
    this.revoking.set(key);
  }

  protected confirmRevoke(): void {
    const key = this.revoking();
    if (!key) {
      return;
    }
    this.revokeInFlight.set(true);
    this.apiKeyService.revoke(key.id).subscribe({
      next: () => {
        this.revokeInFlight.set(false);
        this.revoking.set(null);
        this.load();
        this.toastService.show('API key revoked', `"${key.name}" no longer authenticates.`, 'success');
      },
      error: (error: HttpErrorResponse) => {
        this.revokeInFlight.set(false);
        this.toastService.show('API key', this.messageOf(error, 'The API key could not be revoked.'), 'danger');
      }
    });
  }

  protected toggleInactive(): void {
    this.inactiveOpen.update(open => !open);
  }

  protected status(key: ApiKey): ApiKeyStatus {
    if (key.revokedAt) return 'Revoked';
    if (key.replacedAt) return 'Replaced';
    return this.hasExpired(key) ? 'Expired' : 'Active';
  }

  /** Only the newest working key of a rotation chain can be rotated again. */
  protected canRotate(key: ApiKey): boolean {
    return this.status(key) === 'Active';
  }

  /** Revoked and expired keys authenticate nothing, so there is nothing left to do with them. */
  protected hasActions(key: ApiKey): boolean {
    return !this.isInactive(key);
  }

  private showValue(value: string, title: string, text: string): void {
    this.createdValue.set(value);
    this.createdTitle.set(title);
    this.createdText.set(text);
    this.valueVisible.set(false);
  }

  private load(): void {
    this.loading.set(true);
    this.apiKeyService.list().subscribe({
      next: keys => {
        this.keys.set(keys);
        this.loadFailed.set(false);
        this.loading.set(false);
      },
      error: () => {
        this.keys.set([]);
        this.loadFailed.set(true);
        this.loading.set(false);
      }
    });
  }

  /** Revoked and expired keys are both "inactive" in the group below. */
  private isInactive(key: ApiKey): boolean {
    return !!key.revokedAt || this.hasExpired(key);
  }

  private hasExpired(key: ApiKey): boolean {
    return !!key.expiresAt && new Date(key.expiresAt).getTime() <= Date.now();
  }

  /**
   * The backend's own explanation where there is one; a 401 has no body, so its status is named
   * rather than failing blankly.
   *
   * @param fallback what to say when the response carries no message
   */
  private messageOf(error: HttpErrorResponse, fallback = 'The API key could not be created.'): string {
    const message = error.error?.message;
    if (typeof message === 'string' && message.length > 0) {
      return message;
    }
    if (error.status === 401) {
      return 'Your session has expired — sign in again and retry.';
    }
    // A missing CSRF cookie makes Angular drop the header, which Spring answers 403 to.
    if (error.status === 403) {
      return 'Your session is no longer valid — reload the page and sign in again.';
    }
    return `${fallback} (HTTP ${error.status})`;
  }
}
