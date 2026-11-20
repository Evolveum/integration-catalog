/*
 * Copyright (c) 2010-2026 Evolveum and contributors
 *
 * Licensed under the EUPL-1.2 or later.
 */

// Bootstrap ships no typings; this covers the part InfoTooltip uses.
declare module 'bootstrap/js/src/tooltip.js' {
  export default class Tooltip {
    constructor(element: Element, config?: {
      title?: string | (() => string);
      html?: boolean;
      container?: string | Element | false;
      placement?: 'auto' | 'top' | 'bottom' | 'left' | 'right';
    });
    dispose(): void;
  }
}
