/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, signal, computed, Output, EventEmitter, Input, OnChanges, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApplicationService } from '../../services/application.service';
import { AuthService } from '../../services/auth.service';
import { ImplementationListItem } from '../../models/implementation-list-item.model';

@Component({
  selector: 'app-upload-form-impl',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './upload-form-impl.html',
  styleUrls: ['./upload-form-impl.scss']
})
export class UploadFormImpl implements OnChanges {
  // Input from parent
  @Input() connectorType = signal<string>('');
  @Input() applicationId: string | null = null;

  // Outputs to parent
  @Output() formValid = new EventEmitter<boolean>();
  @Output() formDataChanged = new EventEmitter<any>();

  // Yes / No toggle
  protected readonly isNewVersion = signal<boolean | null>(true);

  // Selected existing implementation (Yes case)
  protected readonly selectedImplementation = signal<ImplementationListItem | null>(null);

  // Show all implementations or just first 4
  protected readonly showAllImplementations = signal<boolean>(false);

  // Form fields
  protected readonly displayName = signal<string>('');
  protected readonly maintainer = signal<string>('');
  protected readonly licenseType = signal<string>('');
  protected readonly implementationDescription = signal<string>('');
  protected readonly browseLink = signal<string>('');
  protected readonly ticketingLink = signal<string>('');
  protected readonly buildFramework = signal<string>('Maven');
  protected readonly checkoutLink = signal<string>('');
  protected readonly pathToProjectDirectory = signal<string>('');
  protected readonly className = signal<string>('');

  // File upload for evolveum-hosted low-code connectors
  protected readonly uploadedFile = signal<{name: string, data: string} | null>(null);
  protected readonly uploadedFileName = signal<string>('');

  // Available options for dropdowns (from backend enum LicenseType)
  protected readonly licenseOptions = ['MIT', 'APACHE_2', 'BSD', 'EUPL'];
  protected maintainerOptions: string[] = [];

  // Existing implementations loaded from backend
  protected readonly existingImplementations = signal<ImplementationListItem[]>([]);
  protected readonly isLoadingImplementations = signal<boolean>(false);

  // Show up to 4 implementations, or all if expanded
  protected readonly displayedImplementations = computed(() => {
    const all = this.existingImplementations();
    return this.showAllImplementations() ? all : all.slice(0, 4);
  });

  constructor(
    private applicationService: ApplicationService,
    private authService: AuthService
  ) {
    const currentUser = this.authService.currentUser();
    if (currentUser) {
      this.maintainer.set(currentUser);

      if (this.authService.currentOrganizationId() != null) {
        this.authService.getOrganizationMembers(currentUser).subscribe({
          next: (members) => {
            this.maintainerOptions = members;
          },
          error: () => {
            this.maintainerOptions = [currentUser];
          }
        });
      } else {
        this.maintainerOptions = [currentUser];
      }
    }
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['applicationId'] && this.applicationId) {
      this.loadImplementations();
    }
  }

  private loadImplementations(): void {
    if (!this.applicationId) return;

    this.isLoadingImplementations.set(true);
    this.applicationService.getImplementationsByApplicationId(this.applicationId).subscribe({
      next: (implementations) => {
        this.existingImplementations.set(implementations);
        this.isLoadingImplementations.set(false);
      },
      error: () => {
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
    this.clearFormFields();
    this.updateFormValidity();
  }

  protected selectImplementation(impl: ImplementationListItem): void {
    this.selectedImplementation.set(impl);

    // Auto-populate form fields from the selected implementation
    this.displayName.set(impl.displayName);

    if (impl.maintainer && !this.maintainerOptions.includes(impl.maintainer)) {
      this.maintainerOptions.push(impl.maintainer);
    }
    this.maintainer.set(impl.maintainer || this.authService.currentUser() || '');

    this.licenseType.set(impl.licenseType || '');
    this.implementationDescription.set(impl.implementationDescription || '');
    this.ticketingLink.set(impl.ticketingLink || '');

    const buildFramework = impl.buildFramework
      ? impl.buildFramework.charAt(0).toUpperCase() + impl.buildFramework.slice(1).toLowerCase()
      : 'Maven';
    this.buildFramework.set(buildFramework);

    this.pathToProjectDirectory.set(impl.pathToProjectDirectory || '');
    this.className.set(impl.className || '');

    // browseLink and checkoutLink are intentionally left empty for new versions
    this.browseLink.set('');
    this.checkoutLink.set('');

    this.updateFormValidity();
  }

  protected toggleShowAll(): void {
    this.showAllImplementations.update(v => !v);
  }

  private clearFormFields(): void {
    this.displayName.set('');
    this.maintainer.set(this.authService.currentUser() || '');
    this.licenseType.set('');
    this.implementationDescription.set('');
    this.browseLink.set('');
    this.ticketingLink.set('');
    this.buildFramework.set('Maven');
    this.checkoutLink.set('');
    this.pathToProjectDirectory.set('');
    this.className.set('');
    this.uploadedFile.set(null);
    this.uploadedFileName.set('');
  }

  protected onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      const file = input.files[0];
      const reader = new FileReader();
      reader.onload = () => {
        const base64String = reader.result as string;
        const base64Data = base64String.split(',')[1];
        this.uploadedFile.set({ name: file.name, data: base64Data });
        this.uploadedFileName.set(file.name);
        this.updateFormValidity();
      };
      reader.readAsDataURL(file);
    }
  }

  protected triggerFileInput(): void {
    const fileInput = document.getElementById('fileUpload') as HTMLInputElement;
    if (fileInput) fileInput.click();
  }

  private updateFormValidity(): void {
    let isValid = false;
    const isEvolveumHosted = this.connectorType() === 'evolveum-hosted';
    const isJavaBased = this.connectorType() === 'java-based';

    if (this.isNewVersion() === true) {
      // Adding a new version: implementation must be selected + form fields valid
      if (this.selectedImplementation() !== null) {
        if (isEvolveumHosted) {
          isValid = this.implementationDescription().trim() !== '' &&
                    this.uploadedFile() !== null;
        } else if (isJavaBased) {
          isValid = this.implementationDescription().trim() !== '' &&
                    this.buildFramework().trim() !== '' &&
                    this.browseLink().trim() !== '' &&
                    this.checkoutLink().trim() !== '';
        } else {
          isValid = this.implementationDescription().trim() !== '';
        }
      }
    } else if (this.isNewVersion() === false) {
      // Creating a new implementation
      if (isEvolveumHosted) {
        isValid = this.displayName().trim() !== '' &&
                  this.implementationDescription().trim() !== '' &&
                  this.uploadedFile() !== null;
      } else if (isJavaBased) {
        isValid = this.displayName().trim() !== '' &&
                  this.licenseType().trim() !== '' &&
                  this.implementationDescription().trim() !== '' &&
                  this.buildFramework().trim() !== '' &&
                  this.browseLink().trim() !== '' &&
                  this.checkoutLink().trim() !== '';
      } else {
        isValid = this.displayName().trim() !== '' &&
                  this.licenseType().trim() !== '' &&
                  this.implementationDescription().trim() !== '';
      }
    }

    const formData = {
      isNewVersion: this.isNewVersion(),
      isEditingVersion: this.isNewVersion() === true && this.selectedImplementation() !== null,
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
      className: this.className(),
      uploadedFile: this.uploadedFile()
    };

    this.formDataChanged.emit(formData);
    this.formValid.emit(isValid);
  }

  protected onFieldChange(): void {
    this.updateFormValidity();
  }
}
