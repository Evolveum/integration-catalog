import { Directive, ElementRef, inject, input } from '@angular/core';
import { Location } from '@angular/common';
import { Router } from '@angular/router';

/**
 * Gives an element that navigates from its (click) handler the browser's "open in a new tab"
 * gestures, which only real links get: Ctrl/Cmd+click and middle-click open the route in a new tab,
 * a plain click is left to the element's own handler. Gestures on a link or button inside the
 * element belong to that control and are ignored.
 *
 * Usage: <tr [appNewTabRoute]="['/applications', id]" (click)="open(id)">
 */
@Directive({
  selector: '[appNewTabRoute]',
  standalone: true,
})
export class NewTabRouteDirective {
  /** Router commands of the page to open. */
  readonly appNewTabRoute = input.required<readonly unknown[]>();

  private readonly element = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
  private readonly router = inject(Router);
  private readonly location = inject(Location);

  constructor() {
    // Capture on the element itself runs before the template's (click), so stopping it here keeps
    // that handler from also navigating the current tab.
    this.element.addEventListener('click', event => {
      if ((event.ctrlKey || event.metaKey) && this.isOwnGesture(event)) {
        event.preventDefault();
        event.stopImmediatePropagation();
        this.openInNewTab();
      }
    }, { capture: true });
    // Without this, a middle press starts the browser's autoscroll instead of producing an auxclick.
    this.element.addEventListener('mousedown', event => {
      if (event.button === 1 && this.isOwnGesture(event)) event.preventDefault();
    });
    this.element.addEventListener('auxclick', event => {
      if (event.button === 1 && this.isOwnGesture(event)) {
        event.preventDefault();
        this.openInNewTab();
      }
    });
  }

  private isOwnGesture(event: MouseEvent): boolean {
    const control = (event.target as Element | null)?.closest('a, button');
    return !control || control === this.element || !this.element.contains(control);
  }

  private openInNewTab(): void {
    const url = this.router.serializeUrl(this.router.createUrlTree([...this.appNewTabRoute()]));
    window.open(this.location.prepareExternalUrl(url), '_blank', 'noopener');
  }
}
