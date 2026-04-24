/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, signal, Input, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { ApplicationService } from '../../services/application.service';
import { AuthService } from '../../services/auth.service';

@Component({
  selector: 'app-request-form',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './request-form.html',
  styleUrls: ['./request-form.scss']
})
export class RequestForm {
  @Input() isRequestModalOpen = signal<boolean>(false);
  @Output() modalClosed = new EventEmitter<void>();
  @Output() requestSubmitted = new EventEmitter<void>();

  protected formData = {
    integrationApplicationName: '',
    integrationMethod: '',
    description: '',
    systemVersion: '',
    contactEmail: '',
    openToCollaborate: false,
    deploymentType: '',
    capabilitiesScope: 'global'
  };
  protected readonly integrationMethods = ['SCIM generic', 'Manual connector', 'REST API', 'Number 4', 'Peekaboo', 'We have been found, RUN'];
  protected readonly isIntegrationMethodDropdownOpen = signal<boolean>(false);
  protected readonly isIntegrationMethodExpanded = signal<boolean>(false);
  protected readonly selectedCapabilities = signal<string[]>([]);
  protected readonly isCapabilitiesDropdownOpen = signal<boolean>(false);
  protected readonly isSubmitting = signal<boolean>(false);
  protected readonly submitSuccess = signal<boolean>(false);
  protected readonly submitError = signal<string | null>(null);
  protected readonly availableCapabilities = signal<string[]>([]);
  protected readonly isLoadingCapabilities = signal<boolean>(false);
  protected readonly specificObjectClassEntries = signal<Array<{
    objectClass: string;
    capabilities: string[];
    isCapabilitiesDropdownOpen: boolean;
  }>>([{ objectClass: '', capabilities: [], isCapabilitiesDropdownOpen: false }]);

  constructor(
    private applicationService: ApplicationService,
    private authService: AuthService
  ) {
    this.loadCapabilities();
  }

  private loadCapabilities(): void {
    this.isLoadingCapabilities.set(true);
    this.applicationService.getCapabilities().subscribe({
      next: (capabilities: string[]) => {
        this.availableCapabilities.set(capabilities);
        this.isLoadingCapabilities.set(false);
      },
      error: (err: HttpErrorResponse) => {
        console.error('Error loading capabilities:', err);
        this.isLoadingCapabilities.set(false);
        // Optionally set a fallback or show an error message
      }
    });
  }

  protected closeRequestModal(): void {
    this.isRequestModalOpen.set(false);
    this.modalClosed.emit();
    this.resetForm();
  }

  protected resetForm(): void {
    this.formData = {
      integrationApplicationName: '',
      integrationMethod: '',
      description: '',
      systemVersion: '',
      contactEmail: '',
      openToCollaborate: false,
      deploymentType: '',
      capabilitiesScope: 'global'
    };
    this.selectedCapabilities.set([]);
    this.specificObjectClassEntries.set([{ objectClass: '', capabilities: [], isCapabilitiesDropdownOpen: false }]);
    this.submitSuccess.set(false);
    this.submitError.set(null);
  }

  protected submitRequest(): void {
    this.isSubmitting.set(true);
    this.submitError.set(null);

    const capabilities: { objectName: string; capabilities: string[] }[] = [];
    if (this.formData.capabilitiesScope === 'global') {
      if (this.selectedCapabilities().length > 0) {
        capabilities.push({ objectName: 'global', capabilities: this.selectedCapabilities() });
      }
    } else {
      for (const entry of this.specificObjectClassEntries()) {
        if (entry.objectClass && entry.capabilities.length > 0) {
          capabilities.push({ objectName: entry.objectClass, capabilities: entry.capabilities });
        }
      }
    }

    const request = {
      integrationApplicationName: this.formData.integrationApplicationName,
      integrationMethod: this.formData.integrationMethod,
      deploymentType: this.formData.deploymentType,
      capabilities,
      description: this.formData.description,
      systemVersion: this.formData.systemVersion,
      contactEmail: this.formData.contactEmail,
      openToCollaborate: this.formData.openToCollaborate,
      requester: this.authService.currentUser()
    };

    this.applicationService.submitRequest(request).subscribe({
      next: () => {
        this.isSubmitting.set(false);
        this.closeRequestModal();
        this.submitSuccess.set(true);
        this.requestSubmitted.emit();
        // Auto-hide success message after 5 seconds
        setTimeout(() => {
          this.submitSuccess.set(false);
        }, 5000);
      },
      error: (err: HttpErrorResponse) => {
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

  protected toggleIntegrationMethodDropdown(): void {
    this.isIntegrationMethodDropdownOpen.update(value => !value);
  }

  protected selectIntegrationMethod(method: string): void {
    this.formData.integrationMethod = method;
    this.isIntegrationMethodDropdownOpen.set(false);
  }

  protected get visibleIntegrationMethods(): string[] {
    return this.isIntegrationMethodExpanded()
      ? this.integrationMethods
      : this.integrationMethods.slice(0, 4);
  }

  protected toggleIntegrationMethodExpanded(event: Event): void {
    event.stopPropagation();
    this.isIntegrationMethodExpanded.update(v => !v);
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
    const checkboxId = `capability-${capability.toLowerCase()}`;
    const checkbox = document.getElementById(checkboxId) as HTMLInputElement;
    if (checkbox) {
      checkbox.checked = false;
    }
  }

  protected formatCapabilityName(capability: string): string {
    return capability
      .replace(/_/g, ' ')
      .toLowerCase()
      .split(' ')
      .map(word => word.charAt(0).toUpperCase() + word.slice(1))
      .join(' ');
  }

  protected addObjectClassEntry(): void {
    this.specificObjectClassEntries.update(entries => [
      ...entries,
      { objectClass: '', capabilities: [], isCapabilitiesDropdownOpen: false }
    ]);
  }

  protected removeObjectClassEntry(index: number): void {
    this.specificObjectClassEntries.update(entries => entries.filter((_, i) => i !== index));
  }

  protected updateObjectClass(index: number, value: string): void {
    this.specificObjectClassEntries.update(entries =>
      entries.map((entry, i) => {
        if (i !== index) return entry;
        const isCapabilitiesDropdownOpen = value ? entry.isCapabilitiesDropdownOpen : false;
        return { ...entry, objectClass: value, isCapabilitiesDropdownOpen };
      })
    );
  }

  protected toggleSpecificCapabilitiesDropdown(index: number): void {
    const entry = this.specificObjectClassEntries()[index];
    if (!entry?.objectClass) return;
    this.specificObjectClassEntries.update(entries =>
      entries.map((e, i) => i === index
        ? { ...e, isCapabilitiesDropdownOpen: !e.isCapabilitiesDropdownOpen }
        : e
      )
    );
  }

  protected onSpecificCapabilityChange(event: Event, index: number, capability: string): void {
    const checked = (event.target as HTMLInputElement).checked;
    this.specificObjectClassEntries.update(entries =>
      entries.map((entry, i) => {
        if (i !== index) return entry;
        const capabilities = checked
          ? [...entry.capabilities, capability]
          : entry.capabilities.filter(c => c !== capability);
        return { ...entry, capabilities };
      })
    );
  }

  protected removeSpecificCapability(index: number, capability: string, event?: Event): void {
    if (event) event.stopPropagation();
    this.specificObjectClassEntries.update(entries =>
      entries.map((entry, i) => i === index
        ? { ...entry, capabilities: entry.capabilities.filter(c => c !== capability) }
        : entry
      )
    );
  }
}
