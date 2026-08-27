import { LitElement, html, css } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import type { QhorusMessage } from '@casehubio/blocks-ui-channel-activity/types.js';
import '@casehubio/blocks-ui-channel-activity/channel-feed.js';

@customElement('qm-commentary-page')
export class QmCommentaryPage extends LitElement {
  @property({ attribute: false }) messages: QhorusMessage[] = [];

  static override styles = css`
    :host { display: flex; flex-direction: column; height: 100%; }
    blocks-channel-feed { flex: 1; }
  `;

  private _formatSender = (sender: string): string => {
    const short = sender.replace(/^commentator-/, '');
    return short.charAt(0).toUpperCase() + short.slice(1);
  };

  override render() {
    return html`
      <blocks-channel-feed
        .messages=${this.messages}
        .channelId=${'quarkmind-commentary'}
        .channelName=${'Commentary'}
        .autoScroll=${true}
        .terminalDimming=${false}
        .eventStyling=${false}
        .formatSender=${this._formatSender}
      ></blocks-channel-feed>
    `;
  }
}
