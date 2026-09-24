/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

import { Pipe, PipeTransform } from '@angular/core';
import EasyMDE from 'easymde';

/**
 * Renders markdown to HTML for an [innerHTML] binding, which Angular sanitizes. Goes through
 * EasyMDE's own renderer, so the text reads the same as in the editor's preview.
 */
@Pipe({ name: 'markdown', standalone: true })
export class MarkdownPipe implements PipeTransform {
  transform(text: string | null | undefined): string {
    if (!text?.trim()) return '';
    // The renderer reads nothing from an editor but its options; an empty set is the defaults.
    // Not in EasyMDE's typings, though it has shipped since the first release, hence the cast.
    const prototype = EasyMDE.prototype as unknown as { markdown(this: unknown, text: string): string };
    return prototype.markdown.call({ options: {} }, text);
  }
}
