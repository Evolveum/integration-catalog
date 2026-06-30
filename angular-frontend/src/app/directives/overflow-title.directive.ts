import { AfterViewInit, Directive, ElementRef, inject } from '@angular/core';

/**
 * Sets a native `title` attribute (the browser's built-in hover tooltip) on the host
 * element only when its text is visually clipped — e.g. by `-webkit-line-clamp` or
 * `text-overflow: ellipsis`. When the text fits, no `title` is set, so no tooltip appears.
 *
 * Usage: <div class="im-desc" appOverflowTitle>{{ longText }}</div>
 */
@Directive({
  selector: '[appOverflowTitle]',
  standalone: true,
})
export class OverflowTitleDirective implements AfterViewInit {
  private readonly el = inject<ElementRef<HTMLElement>>(ElementRef);

  ngAfterViewInit(): void {
    const node = this.el.nativeElement;
    const isClipped =
      node.scrollHeight > node.clientHeight || node.scrollWidth > node.clientWidth;

    if (isClipped) {
      node.setAttribute('title', (node.textContent ?? '').trim());
    } else {
      node.removeAttribute('title');
    }
  }
}
