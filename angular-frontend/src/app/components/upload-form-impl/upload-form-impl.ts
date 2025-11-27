/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, signal, Output, EventEmitter, Input, OnInit, OnChanges, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApplicationService } from '../../services/application.service';
import { ImplementationListItem } from '../../models/implementation-list-item.model';

@Component({
  selector: 'app-upload-form-impl',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './upload-form-impl.html',
  styleUrls: ['./upload-form-impl.css']
})
export class UploadFormImpl implements OnChanges {
  // Input from parent
  @Input() connectorType = signal<string>('');
  @Input() applicationId: string | null = null;

  // Outputs to parent
  @Output() formValid = new EventEmitter<boolean>();
  @Output() formDataChanged = new EventEmitter<any>();

  // Step 3a: Choose between new version or new implementation
  protected readonly isNewVersion = signal<boolean | null>(true); // Preselect "Yes"

  // Step 3b: If new version, select existing implementation
  protected readonly selectedImplementation = signal<ImplementationListItem | null>(null);

  // Track if user is in editing mode (after selecting implementation and clicking Select)
  protected readonly isEditingVersion = signal<boolean>(false);

  // Step 3c: If new implementation, form fields
  protected readonly displayName = signal<string>('');
  protected readonly maintainer = signal<string>('');
  protected readonly licenseType = signal<string>('');
  protected readonly implementationDescription = signal<string>('');
  protected readonly browseLink = signal<string>('');
  protected readonly ticketingLink = signal<string>('');
  protected readonly buildFramework = signal<string>('Maven'); // Default to Maven
  protected readonly checkoutLink = signal<string>('');
  protected readonly pathToProjectDirectory = signal<string>('');

  // File upload for evolveum-hosted low-code connectors
  protected readonly uploadedFile = signal<{name: string, data: string} | null>(null);
  protected readonly uploadedFileName = signal<string>('');

  // Available options for dropdowns (from backend enum LicenseType)
  protected readonly licenseOptions = ['MIT', 'APACHE_2', 'BSD', 'EUPL'];
  protected readonly maintainerOptions: string[] = []; // Empty for now

  // Existing implementations loaded from backend
  protected readonly existingImplementations = signal<ImplementationListItem[]>([]);
  protected readonly isLoadingImplementations = signal<boolean>(false);

  constructor(private applicationService: ApplicationService) {}

  ngOnChanges(changes: SimpleChanges): void {
    // Load implementations when applicationId changes
    if (changes['applicationId'] && this.applicationId) {
      this.loadImplementations();
    }
  }

  private loadImplementations(): void {
    if (!this.applicationId) {
      return;
    }

    this.isLoadingImplementations.set(true);
    this.applicationService.getImplementationsByApplicationId(this.applicationId).subscribe({
      next: (implementations) => {
        this.existingImplementations.set(implementations);
        this.isLoadingImplementations.set(false);
      },
      error: (error) => {
        console.error('Failed to load implementations:', error);
        this.existingImplementations.set([]);
        this.isLoadingImplementations.set(false);
      }
    });
  }

  protected selectNewVersion(): void {
    this.isNewVersion.set(true);
    this.updateFormValidity();
  }

  protected selectNewImplementation(): void {
    this.isNewVersion.set(false);
    this.selectedImplementation.set(null);
    this.updateFormValidity();
  }

  protected selectImplementation(impl: ImplementationListItem): void {
    this.selectedImplementation.set(impl);
    this.updateFormValidity();
  }

  public confirmImplementationSelection(): void {
    const impl = this.selectedImplementation();
    if (!impl) return;

    // Enter editing mode and populate form fields
    this.isEditingVersion.set(true);

    // Populate all fields from the selected implementation
    this.displayName.set(impl.displayName);
    this.maintainer.set(impl.maintainer);

    // Add maintainer to options if not already present
    if (impl.maintainer && !this.maintainerOptions.includes(impl.maintainer)) {
      this.maintainerOptions.push(impl.maintainer);
    }

    this.licenseType.set(impl.licenseType);
    this.implementationDescription.set(impl.implementationDescription);
    this.browseLink.set(impl.browseLink);
    this.ticketingLink.set(impl.ticketingLink);

    // Transform build framework from uppercase (MAVEN/GRADLE) to capitalized (Maven/Gradle)
    const buildFramework = impl.buildFramework ?
      impl.buildFramework.charAt(0).toUpperCase() + impl.buildFramework.slice(1).toLowerCase()
      : '';
    this.buildFramework.set(buildFramework);

    this.checkoutLink.set(impl.checkoutLink);
    this.pathToProjectDirectory.set(impl.pathToProjectDirectory);

    this.updateFormValidity();
  }

  public cancelEditing(): void {
    // Exit editing mode and go back to implementation selection
    this.isEditingVersion.set(false);

    // Clear form fields
    this.displayName.set('');
    this.maintainer.set('');
    this.licenseType.set('');
    this.implementationDescription.set('');
    this.browseLink.set('');
    this.ticketingLink.set('');
    this.buildFramework.set('');
    this.checkoutLink.set('');
    this.pathToProjectDirectory.set('');
    this.uploadedFile.set(null);
    this.uploadedFileName.set('');

    this.updateFormValidity();
  }

  protected onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      const file = input.files[0];

      // Read file as base64
      const reader = new FileReader();
      reader.onload = () => {
        const base64String = reader.result as string;
        // Remove the data URL prefix (e.g., "data:application/zip;base64,")
        const base64Data = base64String.split(',')[1];

        this.uploadedFile.set({
          name: file.name,
          data: base64Data
        } as any);
        this.uploadedFileName.set(file.name);
        this.updateFormValidity();
      };
      reader.readAsDataURL(file);
    }
  }

  protected triggerFileInput(): void {
    const fileInput = document.getElementById('fileUpload') as HTMLInputElement;
    if (fileInput) {
      fileInput.click();
    }
  }

  private updateFormValidity(): void {
    let isValid = false;
    const isEvolveumHosted = this.connectorType() === 'evolveum-hosted';
    const isOwnRepo = this.connectorType() === 'own-repo';
    const isJavaBased = this.connectorType() === 'java-based';

    if (this.isEditingVersion()) {
      // Editing a version - validate required editable fields
      if (isEvolveumHosted) {
        // For evolveum-hosted: Implementation Description and File are REQUIRED
        isValid = this.implementationDescription().trim() !== '' &&
                  this.uploadedFile() !== null;
      } else if (isJavaBased) {
        // For java-based: Implementation Description, Build Framework, Browse Link, Checkout Link are REQUIRED
        isValid = this.implementationDescription().trim() !== '' &&
                  this.buildFramework().trim() !== '' &&
                  this.browseLink().trim() !== '' &&
                  this.checkoutLink().trim() !== '';
      } else {
        // For own-repo: Only Implementation Description is REQUIRED (build framework not shown)
        isValid = this.implementationDescription().trim() !== '';
      }
    } else if (this.isNewVersion() === false) {
      // Creating new implementation - validate required fields
      if (isEvolveumHosted) {
        // For evolveum-hosted: Display Name, Implementation Description, File are REQUIRED
        isValid = this.displayName().trim() !== '' &&
                  this.implementationDescription().trim() !== '' &&
                  this.uploadedFile() !== null;
      } else if (isJavaBased) {
        // For java-based: Display Name, License Type, Implementation Description, Build Framework, Browse Link, Checkout Link are REQUIRED
        isValid = this.displayName().trim() !== '' &&
                  this.licenseType().trim() !== '' &&
                  this.implementationDescription().trim() !== '' &&
                  this.buildFramework().trim() !== '' &&
                  this.browseLink().trim() !== '' &&
                  this.checkoutLink().trim() !== '';
      } else {
        // For own-repo: Display Name, License Type, Implementation Description are REQUIRED (build framework not shown)
        isValid = this.displayName().trim() !== '' &&
                  this.licenseType().trim() !== '' &&
                  this.implementationDescription().trim() !== '';
      }
    } else if (this.isNewVersion() === true && this.selectedImplementation() !== null && !this.isEditingVersion()) {
      // Adding new version - valid when implementation selected
      isValid = true;
    }

    const formData = {
      isNewVersion: this.isNewVersion(),
      isEditingVersion: this.isEditingVersion(),
      selectedImplementation: this.selectedImplementation(),
      displayName: this.displayName(),
      maintainer: this.maintainer(),
      licenseType: this.licenseType(),
      implementationDescription: this.implementationDescription(),
      browseLink: this.browseLink(),
      ticketingLink: this.ticketingLink(),
      buildFramework: this.buildFramework(),
      checkoutLink: this.checkoutLink(),
      pathToProjectDirectory: this.pathToProjectDirectory(),
      uploadedFile: this.uploadedFile()
    };

    this.formDataChanged.emit(formData);
    this.formValid.emit(isValid);
  }

  protected onFieldChange(): void {
    this.updateFormValidity();
  }
}
