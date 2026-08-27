import { LitElement, html, css } from 'lit';
import { customElement, property } from 'lit/decorators.js';

interface StrategyData {
  strategyId: string;
  archetype: string;
  confidence: number;
  pivotCount: number;
}

@customElement('qm-strategy-page')
export class QmStrategyPage extends LitElement {
  @property({ attribute: false }) data: StrategyData | null = null;

  static override styles = css`
    :host { display: block; padding: 10px; font-size: 12px; color: #ccc; }
    .strategy-label { font-weight: bold; color: #88bbff; margin-bottom: 4px; }
    .strategy-value { font-size: 16px; margin-bottom: 8px; }
    .strategy-row { font-size: 12px; margin: 2px 0; }
    .strategy-row span { color: #999; }
  `;

  override render() {
    if (!this.data) return html`<div>No strategy data</div>`;
    const s = this.data;
    return html`
      <div>
        <div class="strategy-label">Active Strategy</div>
        <div class="strategy-value">${s.strategyId}</div>
        <div class="strategy-row"><span>Archetype:</span> ${s.archetype}</div>
        <div class="strategy-row"><span>Confidence:</span> ${(s.confidence * 100).toFixed(0)}%</div>
        <div class="strategy-row"><span>Pivots:</span> ${s.pivotCount}</div>
      </div>
    `;
  }
}
