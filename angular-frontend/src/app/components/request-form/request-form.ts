/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, signal, Input, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApplicationService } from '../../services/application.service';

@Component({
  selector: 'app-request-form',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './request-form.html',
  styleUrls: ['./request-form.css']
})
export class RequestForm {
  @Input() isRequestModalOpen = signal<boolean>(false);
  @Output() modalClosed = new EventEmitter<void>();

  protected formData = {
    integrationApplicationName: '',
    baseUrl: '',
    description: '',
    systemVersion: '',
    email: '',
    collab: false
  };
  protected readonly selectedCapabilities = signal<string[]>([]);
  protected readonly isCapabilitiesDropdownOpen = signal<boolean>(false);
  protected readonly isSubmitting = signal<boolean>(false);
  protected readonly submitSuccess = signal<boolean>(false);
  protected readonly submitError = signal<string | null>(null);

  constructor(private applicationService: ApplicationService) {}

  protected closeRequestModal(): void {
    this.isRequestModalOpen.set(false);
    this.modalClosed.emit();
    this.resetForm();
  }

  protected resetForm(): void {
    this.formData = {
      integrationApplicationName: '',
      baseUrl: '',
      description: '',
      systemVersion: '',
      email: '',
      collab: false
    };
    this.selectedCapabilities.set([]);
    this.submitSuccess.set(false);
    this.submitError.set(null);
  }

  protected submitRequest(): void {
    this.isSubmitting.set(true);
    this.submitError.set(null);

    const request = {
      integrationApplicationName: this.formData.integrationApplicationName,
      baseUrl: this.formData.baseUrl,
      capabilities: this.selectedCapabilities(),
      description: this.formData.description,
      systemVersion: this.formData.systemVersion,
      email: this.formData.email,
      collab: this.formData.collab,
      requester: this.formData.email || 'anonymous'
    };

    this.applicationService.submitRequest(request).subscribe({
      next: () => {
        this.isSubmitting.set(false);
        this.closeRequestModal();
        this.submitSuccess.set(true);
        // Auto-hide success message after 5 seconds
        setTimeout(() => {
          this.submitSuccess.set(false);
        }, 5000);
      },
      error: (err: any) => {
        this.isSubmitting.set(false);
        this.submitError.set('Failed to submit request. Please try again.');
        console.error('Error submitting request:', err);
      }
    });
  }

  protected closeSuccessMessage(): void {
    this.submitSuccess.set(false);
  }

  protected toggleCapabilitiesDropdown(): void {
    this.isCapabilitiesDropdownOpen.update(value => !value);
  }

  protected closeCapabilitiesDropdown(): void {
    this.isCapabilitiesDropdownOpen.set(false);
  }

  protected onCapabilityChange(event: Event, capability: string): void {
    const checked = (event.target as HTMLInputElement).checked;
    if (checked) {
      this.selectedCapabilities.update(caps => [...caps, capability]);
    } else {
      this.selectedCapabilities.update(caps => caps.filter(cap => cap !== capability));
    }
  }

  protected removeCapability(capability: string, event?: Event): void {
    if (event) {
      event.stopPropagation();
    }
    this.selectedCapabilities.update(caps => caps.filter(cap => cap !== capability));
    // Uncheck the corresponding checkbox
    const checkbox = document.getElementById(`capability-${capability.toLowerCase().replace(/\s+/g, '-')}`) as HTMLInputElement;
    if (checkbox) {
      checkbox.checked = false;
    }
  }
}
