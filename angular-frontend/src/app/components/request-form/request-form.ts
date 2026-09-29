/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, signal, computed, Input, Output, EventEmitter, ViewChild, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { ApplicationService } from '../../services/application.service';
import { AuthService } from '../../services/auth.service';
import { ImCapabilityPicker, imCapabilitiesValid } from '../im-capability-picker/im-capability-picker';
import { IntegrationMethodObjectCapabilities } from '../../models/application-detail.model';

interface IntegrationMethodOption {
  id: number;
  displayName: string;
}

@Component({
  selector: 'app-request-form',
  standalone: true,
  imports: [CommonModule, FormsModule, ImCapabilityPicker],
  templateUrl: './request-form.html',
  styleUrls: ['./request-form.scss']
})
export class RequestForm implements OnInit {
  @Input() isRequestModalOpen = signal<boolean>(false);
  @Output() modalClosed = new EventEmitter<void>();
  @Output() requestSubmitted = new EventEmitter<void>();

  @ViewChild(ImCapabilityPicker) private capabilityPicker?: ImCapabilityPicker;

  protected formData = {
    integrationApplicationName: '',
    integrationMethod: null as IntegrationMethodOption | null,
    description: '',
    integrationNeed: '',
    systemVersion: '',
    contactEmail: '',
    openToCollaborate: false,
    deploymentType: ''
  };
  protected readonly integrationMethods = signal<IntegrationMethodOption[]>([]);
  protected readonly isIntegrationMethodDropdownOpen = signal<boolean>(false);
  protected readonly isIntegrationMethodExpanded = signal<boolean>(false);
  protected readonly capabilityGroups = signal<IntegrationMethodObjectCapabilities[]>([]);
  // Capabilities stay optional on a request, but every object given needs at least one Supported.
  protected readonly capabilitiesValid = computed(() => imCapabilitiesValid(this.capabilityGroups()));
  protected readonly isSubmitting = signal<boolean>(false);
  protected readonly submitSuccess = signal<boolean>(false);
  protected readonly submitError = signal<string | null>(null);

  constructor(
    private applicationService: ApplicationService,
    private authService: AuthService
  ) {}

  ngOnInit(): void {
    this.applicationService.getIntegrationMethodTypes().subscribe({
      next: (types) => this.integrationMethods.set(types),
      error: (err) => console.error('Failed to load integration method types', err)
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
      integrationMethod: null,
      description: '',
      integrationNeed: '',
      systemVersion: '',
      contactEmail: '',
      openToCollaborate: false,
      deploymentType: ''
    };
    this.capabilityGroups.set([]);
    this.capabilityPicker?.reset();
    this.submitSuccess.set(false);
    this.submitError.set(null);
  }

  protected onCapabilitiesChange(groups: IntegrationMethodObjectCapabilities[]): void {
    this.capabilityGroups.set(groups);
  }

  protected submitRequest(): void {
    this.isSubmitting.set(true);
    this.submitError.set(null);

    const request = {
      integrationApplicationName: this.formData.integrationApplicationName,
      integrationMethodTypeId: this.formData.integrationMethod?.id ?? null,
      deploymentType: this.formData.deploymentType,
      capabilities: this.capabilityGroups(),
      description: this.formData.description,
      integrationNeed: this.formData.integrationNeed,
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

  protected toggleIntegrationMethodDropdown(): void {
    this.isIntegrationMethodDropdownOpen.update(value => !value);
  }

  protected selectIntegrationMethod(method: IntegrationMethodOption): void {
    this.formData.integrationMethod = method;
    this.isIntegrationMethodDropdownOpen.set(false);
  }

  protected get visibleIntegrationMethods(): IntegrationMethodOption[] {
    return this.isIntegrationMethodExpanded()
      ? this.integrationMethods()
      : this.integrationMethods().slice(0, 4);
  }

  protected toggleIntegrationMethodExpanded(event: Event): void {
    event.stopPropagation();
    this.isIntegrationMethodExpanded.update(v => !v);
  }

}
