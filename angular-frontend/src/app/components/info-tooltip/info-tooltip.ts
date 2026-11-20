/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { afterNextRender, Component, DestroyRef, ElementRef, inject, viewChild } from '@angular/core';
import Tooltip from 'bootstrap/js/src/tooltip.js';

/**
 * The info icon next to a field label, showing its projected content in a Bootstrap tooltip on hover or focus.
 * The content may use <strong> and <br>.
 */
@Component({
  selector: 'app-info-tooltip',
  standalone: true,
  templateUrl: './info-tooltip.html',
  styleUrls: ['./info-tooltip.scss']
})
export class InfoTooltip {
  private readonly trigger = viewChild.required<ElementRef<HTMLElement>>('trigger');
  private readonly content = viewChild.required<ElementRef<HTMLElement>>('content');

  constructor() {
    const destroyRef = inject(DestroyRef);
    afterNextRender(() => {
      // Read on every show, so content with bindings stays current; on body, so modals and scroll boxes don't clip it.
      const tooltip = new Tooltip(this.trigger().nativeElement, {
        title: () => this.content().nativeElement.innerHTML,
        html: true,
        container: 'body'
      });
      destroyRef.onDestroy(() => tooltip.dispose());
    });
  }
}
