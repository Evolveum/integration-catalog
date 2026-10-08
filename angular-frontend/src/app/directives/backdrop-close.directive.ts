import { Directive, HostListener, input, output } from '@angular/core';

/**
 * Closes a modal from its overlay only on a click made entirely outside the dialog. A plain
 * (click) on the overlay also fires when a press starts inside the dialog (e.g. selecting text in
 * an input) and is released outside, because the browser dispatches that click to the overlay.
 *
 * Usage: <div class="overlay" [appBackdropClose]="dialog" (backdropClose)="close()">
 *          <div #dialog class="dialog">…</div>
 */
@Directive({
  selector: '[appBackdropClose]',
  standalone: true,
})
export class BackdropCloseDirective {
  /** The dialog; presses and releases inside it never close the modal. */
  readonly appBackdropClose = input.required<HTMLElement>();
  readonly backdropClose = output<void>();

  private pressedOutside = false;
  private releasedOutside = false;

  @HostListener('mousedown', ['$event'])
  onMouseDown(event: MouseEvent): void {
    this.pressedOutside = this.isOutside(event);
  }

  @HostListener('mouseup', ['$event'])
  onMouseUp(event: MouseEvent): void {
    this.releasedOutside = this.isOutside(event);
  }

  // Keyed on click rather than mouseup: dragging the overlay's scrollbar presses and releases on
  // the overlay too, but produces no click.
  @HostListener('click', ['$event'])
  onClick(event: MouseEvent): void {
    if (this.pressedOutside && this.releasedOutside && this.isOutside(event)) {
      this.backdropClose.emit();
    }
    this.pressedOutside = false;
    this.releasedOutside = false;
  }

  private isOutside(event: MouseEvent): boolean {
    return !this.appBackdropClose().contains(event.target as Node);
  }
}
