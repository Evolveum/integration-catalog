/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, computed, EventEmitter, Input, Output, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

/** What the user filled in; a null expiration means the key never expires. */
export interface CreateApiKeyRequest {
  name: string;
  expiresAt: string | null;
}

/** One preset of the expiration row; days are added to the moment of creation. */
interface ExpirationPreset {
  label: string;
  days: number;
}

const EXPIRATION_PRESETS: ExpirationPreset[] = [
  { label: '1 day', days: 1 },
  { label: '7 days', days: 7 },
  { label: '30 days', days: 30 },
  { label: '90 days', days: 90 },
  { label: '180 days', days: 180 }
];

/** Collects the name and expiration of a new key; the parent performs the creation. */
@Component({
  selector: 'app-create-api-key-modal',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './create-api-key-modal.html',
  styleUrls: ['./create-api-key-modal.scss']
})
export class CreateApiKeyModal {
  /** True while the key is being minted: the modal stays open but cannot be submitted again. */
  @Input() busy = false;

  @Output() closed = new EventEmitter<void>();
  @Output() create = new EventEmitter<CreateApiKeyRequest>();

  protected readonly presets = EXPIRATION_PRESETS;

  protected readonly name = signal('');
  protected readonly selectedPreset = signal<ExpirationPreset | null>(EXPIRATION_PRESETS[0]);
  /** Date picked under the Custom chip, in the yyyy-MM-dd the date input uses. */
  protected readonly customDate = signal('');

  /** Earliest date the picker accepts, so a key never expires the day it is made. */
  protected readonly minCustomDate = this.dateInputValue(this.plusDays(new Date(), 1));

  protected readonly customSelected = computed(() => this.selectedPreset() === null);

  protected readonly canSubmit = computed(() =>
    this.name().trim().length > 0 && (!this.customSelected() || this.customDate().length > 0));

  protected selectPreset(preset: ExpirationPreset | null): void {
    this.selectedPreset.set(preset);
    if (preset !== null) this.customDate.set('');
  }

  protected onClose(): void {
    this.closed.emit();
  }

  protected onCreate(): void {
    if (!this.canSubmit() || this.busy) return;
    this.create.emit({ name: this.name().trim(), expiresAt: this.expiresAt() });
  }

  /** The chosen expiration as an ISO instant: end of the custom day, or the preset's offset. */
  private expiresAt(): string | null {
    const preset = this.selectedPreset();
    if (preset) return this.plusDays(new Date(), preset.days).toISOString();
    const custom = new Date(`${this.customDate()}T23:59:59`);
    return isNaN(custom.getTime()) ? null : custom.toISOString();
  }

  private plusDays(from: Date, days: number): Date {
    const result = new Date(from);
    result.setDate(result.getDate() + days);
    return result;
  }

  private dateInputValue(date: Date): string {
    const month = `${date.getMonth() + 1}`.padStart(2, '0');
    const day = `${date.getDate()}`.padStart(2, '0');
    return `${date.getFullYear()}-${month}-${day}`;
  }
}
