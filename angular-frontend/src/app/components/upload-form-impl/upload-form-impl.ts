/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, signal, Output, EventEmitter, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

interface ExistingImplementation {
  id: string;
  name: string;
  description: string;
  publishedDate: string;
}

@Component({
  selector: 'app-upload-form-impl',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './upload-form-impl.html',
  styleUrls: ['./upload-form-impl.css']
})
export class UploadFormImpl {
  // Input from parent
  @Input() connectorType = signal<string>('');
  @Input() applicationId: string | null = null;

  // Outputs to parent
  @Output() formValid = new EventEmitter<boolean>();
  @Output() formDataChanged = new EventEmitter<any>();

  // Step 3a: Choose between new version or new implementation
  protected readonly isNewVersion = signal<boolean | null>(true); // Preselect "Yes"

  // Step 3b: If new version, select existing implementation
  protected readonly selectedImplementation = signal<ExistingImplementation | null>(null);

  // Step 3c: If new implementation, form fields
  protected readonly displayName = signal<string>('');
  protected readonly maintainer = signal<string>('');
  protected readonly licenseType = signal<string>('');
  protected readonly implementationDescription = signal<string>('');
  protected readonly browseLink = signal<string>('');
  protected readonly ticketingLink = signal<string>('');
  protected readonly buildFramework = signal<string>(''); // No preselected value
  protected readonly checkoutLink = signal<string>('');
  protected readonly pathToProjectDirectory = signal<string>('');

  // Available options for dropdowns (from backend enum LicenseType)
  protected readonly licenseOptions = ['MIT', 'APACHE_2', 'BSD', 'EUPL'];
  protected readonly maintainerOptions: string[] = []; // Empty for now

  // Mock data for existing implementations - TODO: Replace with actual service call
  protected readonly existingImplementations = signal<ExistingImplementation[]>([
    {
      id: '1',
      name: 'SCIM Connector',
      description: 'The future of shared documents. Power your brand\'s communication across departments with seamless permissions and real-time updates.',
      publishedDate: 'Sep 20, 2025'
    },
    {
      id: '2',
      name: 'Nimbus Sync Connector',
      description: 'The future of shared documents. Power your brand\'s communication across departments with seamless permissions and real-time updates.',
      publishedDate: 'Sep 20, 2025'
    },
    {
      id: '3',
      name: 'LDAP/AD',
      description: 'The future of shared documents. Power your brand\'s communication across departments with seamless permissions and real-time updates.',
      publishedDate: 'Sep 20, 2025'
    }
  ]);

  constructor() {}

  protected selectNewVersion(): void {
    this.isNewVersion.set(true);
    this.updateFormValidity();
  }

  protected selectNewImplementation(): void {
    this.isNewVersion.set(false);
    this.selectedImplementation.set(null);
    this.updateFormValidity();
  }

  protected selectImplementation(impl: ExistingImplementation): void {
    this.selectedImplementation.set(impl);
    this.updateFormValidity();
  }

  private updateFormValidity(): void {
    let isValid = false;

    if (this.isNewVersion() === false) {
      // Creating new implementation - validate required fields:
      // Display Name, License Type, Implementation Description, Build Framework are REQUIRED
      isValid = this.displayName().trim() !== '' &&
                this.licenseType().trim() !== '' &&
                this.implementationDescription().trim() !== '' &&
                this.buildFramework().trim() !== '';
    } else if (this.isNewVersion() === true && this.selectedImplementation() !== null) {
      // Adding new version - valid when implementation selected
      isValid = true;
    }

    const formData = {
      isNewVersion: this.isNewVersion(),
      selectedImplementation: this.selectedImplementation(),
      newImplementation: {
        displayName: this.displayName(),
        maintainer: this.maintainer(),
        licenseType: this.licenseType(),
        description: this.implementationDescription(),
        browseLink: this.browseLink(),
        ticketingLink: this.ticketingLink(),
        buildFramework: this.buildFramework(),
        checkoutLink: this.checkoutLink(),
        pathToProjectDirectory: this.pathToProjectDirectory()
      }
    };

    this.formDataChanged.emit(formData);
    this.formValid.emit(isValid);
  }

  protected onFieldChange(): void {
    this.updateFormValidity();
  }
}
