import { PagesSplitWorkbench } from '@casehubio/pages-ui-components/split-workbench';
import '@casehubio/mosaic-detail-pane';
import './workbench/qm-pattern-page.js';
import './workbench/qm-coaching-page.js';
import './workbench/qm-strategy-page.js';
import './workbench/qm-commentary-page.js';
import './workbench/qm-workbench-controller.js';

// Preserve the visualizer's tag when using the component's new package.
if (!customElements.get('blocks-split-workbench')) {
  customElements.define('blocks-split-workbench', class extends PagesSplitWorkbench {});
}
