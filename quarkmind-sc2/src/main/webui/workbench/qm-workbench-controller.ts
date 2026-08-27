import { emitPagesEvent } from '@casehubio/pages-component';

export function initWorkbench(): void {
  emitPagesEvent(document, 'qm-workbench:selected', {});
}

document.addEventListener('DOMContentLoaded', () => {
  requestAnimationFrame(() => initWorkbench());
});
