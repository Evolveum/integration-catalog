/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Component, ElementRef, OnDestroy, effect, input, output, untracked, viewChild } from '@angular/core';
import EasyMDE from 'easymde';
import { MarkdownPipe } from '../../core/markdown.pipe';

/**
 * The markdown editor of the integration tutorial, for other long texts. Disabled, it shows the text
 * rendered instead. Side-by-side is left out: it goes full screen, which breaks inside a modal.
 */
@Component({
  selector: 'app-markdown-editor',
  standalone: true,
  imports: [MarkdownPipe],
  templateUrl: './markdown-editor.html',
  styleUrls: ['./markdown-editor.scss']
})
export class MarkdownEditor implements OnDestroy {
  readonly value = input<string>('');
  readonly placeholder = input<string>('');
  readonly disabled = input<boolean>(false);
  readonly valueChange = output<string>();

  private readonly textarea = viewChild<ElementRef<HTMLTextAreaElement>>('editor');
  private easyMde: EasyMDE | null = null;

  constructor() {
    // The textarea comes and goes with the disabled state; the editor follows it.
    effect(() => {
      const el = this.textarea()?.nativeElement;
      if (el && !this.easyMde) {
        untracked(() => this.createEditor(el));
      } else if (!el && this.easyMde) {
        this.easyMde = null;
      }
    });
    // A value set from outside (a reset, a picked connector) reaches an editor already open.
    effect(() => {
      const value = this.value() ?? '';
      if (this.easyMde && this.easyMde.value() !== value) {
        this.easyMde.value(value);
      }
    });
  }

  ngOnDestroy(): void {
    this.easyMde?.toTextArea();
    this.easyMde = null;
  }

  private createEditor(el: HTMLTextAreaElement): void {
    this.easyMde = new EasyMDE({
      element: el,
      initialValue: this.value() ?? '',
      placeholder: this.placeholder(),
      spellChecker: false,
      status: false,
      minHeight: '120px',
      toolbar: ['bold', 'italic', 'strikethrough', '|',
                'heading-1', 'heading-2', '|',
                'unordered-list', 'ordered-list', '|',
                'link', '|', 'preview'],
    });
    this.easyMde.codemirror.on('change', () => this.valueChange.emit(this.easyMde!.value()));
  }
}
