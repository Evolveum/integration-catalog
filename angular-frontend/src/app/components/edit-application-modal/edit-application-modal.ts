/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import {
  Component, Input, Output, EventEmitter, OnInit,
  signal, computed
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApplicationService } from '../../services/application.service';
import { ApplicationDetail } from '../../models/application-detail.model';

@Component({
  selector: 'app-edit-application-modal',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './edit-application-modal.html',
  styleUrls: ['./edit-application-modal.scss']
})
export class EditApplicationModal implements OnInit {
  @Input() appId = '';
  @Input() application: ApplicationDetail | null = null;
  @Output() close = new EventEmitter<void>();
  @Output() saved = new EventEmitter<void>();

  protected readonly isSaving = signal<boolean>(false);
  protected readonly saveError = signal<string>('');
  protected readonly saveSuccess = signal<string>('');

  // Form fields
  protected readonly displayName = signal<string>('');
  protected readonly description = signal<string>('');
  protected readonly logoFile = signal<File | null>(null);

  // Validation
  protected readonly displayNameInvalid = computed(() => {
    const name = this.displayName();
    return name.length > 0 && name.length < 3;
  });

  protected readonly isValid = computed(() => {
    return this.displayName().trim().length >= 3;
  });

  constructor(
    private appService: ApplicationService
  ) {}

  ngOnInit(): void {
    if (this.application) {
      this.displayName.set(this.application.displayName || '');
      this.description.set(this.application.description || '');
    }
  }

  protected onDisplayNameInput(event: Event): void {
    const target = event.target as HTMLInputElement;
    this.displayName.set(target.value ?? '');
  }

  protected onDescriptionInput(event: Event): void {
    const target = event.target as HTMLTextAreaElement;
    this.description.set(target.value ?? '');
  }

  protected onLogoSelect(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      this.logoFile.set(input.files[0]);
    }
  }

  protected clearLogo(): void {
    this.logoFile.set(null);
    const input = document.getElementById('logo-input') as HTMLInputElement;
    if (input) input.value = '';
  }

  protected onClose(): void {
    this.saveError.set('');
    this.saveSuccess.set('');
    this.close.emit();
  }

  protected save(): void {
    this.saveError.set('');
    this.saveSuccess.set('');

    if (!this.isValid()) {
      return;
    }

    this.isSaving.set(true);

    // Update application details
    this.appService.updateApplication(this.appId, {
      displayName: this.displayName().trim(),
      description: this.description().trim() || null
    }).subscribe({
      next: () => {
        // Upload logo if selected
        const file = this.logoFile();
        if (file) {
          this.appService.uploadLogo(this.appId, file).subscribe({
            next: () => {
              this.isSaving.set(false);
              this.saveSuccess.set('Application updated successfully');
              setTimeout(() => this.saved.emit(), 500);
            },
            error: (err) => {
              this.isSaving.set(false);
              console.error('Logo upload failed', err);
              // Still report success since the app details were saved
              this.saveSuccess.set('Application updated (logo upload failed)');
              setTimeout(() => this.saved.emit(), 1500);
            }
          });
        } else {
          this.isSaving.set(false);
          this.saveSuccess.set('Application updated successfully');
          setTimeout(() => this.saved.emit(), 500);
        }
      },
      error: (err) => {
        this.isSaving.set(false);
        console.error('Update application failed', err);
        const e = err as { error?: { message?: string } | string; message?: string };
        const message = (typeof e?.error === 'object' ? e.error?.message : e?.error) || e?.message;
        this.saveError.set(message || 'Failed to save changes. Please try again.');
      }
    });
  }
}
