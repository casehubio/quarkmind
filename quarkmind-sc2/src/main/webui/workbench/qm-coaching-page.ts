import { LitElement, html, css, nothing } from 'lit';
import { customElement, property } from 'lit/decorators.js';

interface CoachingItem {
  advice: string;
  domain: string;
  urgency?: string;
  gameFrame: number;
  correlationId: string;
  complianceStatus?: string;
}

@customElement('qm-coaching-page')
export class QmCoachingPage extends LitElement {
  @property({ attribute: false }) data: CoachingItem[] = [];

  static override styles = css`
    :host { display: block; padding: 10px; font-size: 12px; color: #ccc; }
    .coaching-item { border-bottom: 1px solid #1a1a3e; padding: 6px 0; }
    .coaching-header { font-size: 11px; color: #88bbff; }
    .coaching-advice { margin: 4px 0; }
    .coaching-status { font-size: 11px; color: #999; }
    .coaching-controls { display: flex; align-items: center; gap: 4px; margin-top: 4px; }
    .coaching-btn { cursor: pointer; padding: 2px 8px; border: 1px solid #555; background: #222; color: #ccc; border-radius: 3px; font-size: 12px; }
    .coaching-accept:hover { background: #1a3a1a; border-color: #4a4; }
    .coaching-dismiss:hover { background: #3a1a1a; border-color: #a44; }
  `;

  private _formatTime(gameFrame: number): string {
    const secs = Math.floor(gameFrame / 22.4);
    const mins = Math.floor(secs / 60);
    const rem = secs % 60;
    return `${mins}:${String(rem).padStart(2, '0')}`;
  }

  private _respond(correlationId: string, response: string): void {
    this.dispatchEvent(new CustomEvent('coaching-response', {
      bubbles: true, composed: true,
      detail: { correlationId, response },
    }));
  }

  override render() {
    if (!this.data.length) {
      return html`<div>No coaching advice yet</div>`;
    }
    return html`${this.data.map(c => this._renderItem(c))}`;
  }

  private _renderItem(c: CoachingItem) {
    const isPending = !c.complianceStatus;
    return html`
      <div class="coaching-item">
        <div class="coaching-header">${this._formatTime(c.gameFrame)} [${c.domain}] ${c.urgency ?? ''}</div>
        <div class="coaching-advice">${c.advice}</div>
        <div class="coaching-controls">
          ${isPending ? html`
            <button class="coaching-btn coaching-accept" @click=${() => this._respond(c.correlationId, 'DONE')}>✓ Accept</button>
            <button class="coaching-btn coaching-dismiss" @click=${() => this._respond(c.correlationId, 'DECLINE')}>✗ Dismiss</button>
          ` : nothing}
          <span class="coaching-status">${c.complianceStatus ?? '⏳ Pending'}</span>
        </div>
      </div>
    `;
  }
}
