/*
 * Copyright (c) 2010-2025 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, inject } from '@angular/core';
import { EnvironmentService } from '../../services/environment.service';

@Component({
  selector: 'app-staging-banner',
  standalone: true,
  templateUrl: './staging-banner.html',
  styleUrls: ['./staging-banner.scss']
})
export class StagingBanner {
  protected readonly environmentService = inject(EnvironmentService);
}
