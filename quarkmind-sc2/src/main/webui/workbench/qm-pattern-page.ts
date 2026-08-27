import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';

interface Assessment {
  archetype: string;
  confidence: number;
  rationale?: string;
}

interface CounterUnit { units: string[]; action?: string; }
interface CounterInfo { strongCounters?: CounterUnit[]; weakCounters?: CounterUnit[]; }
interface EnrichedAssessment { assessment: Assessment; counters?: CounterInfo; }
interface PatternData { assessments: EnrichedAssessment[]; }

@customElement('qm-pattern-page')
export class QmPatternPage extends LitElement {
  @property({ attribute: false }) data: PatternData | null = null;
  @state() private _expandedIndex = 0;

  static override styles = css`
    :host { display: block; padding: 10px; font-size: 12px; color: #ccc; }
    .assessment-item { margin-bottom: 8px; }
    .assessment-header { cursor: pointer; font-weight: bold; padding: 4px 0; }
    .confidence-bar { background: #1a1a3e; height: 4px; border-radius: 2px; margin: 2px 0 6px; }
    .bar-fill { height: 4px; border-radius: 2px; }
    .assessment-body { padding: 4px 0 8px; }
    .rationale { font-size: 11px; color: #999; margin-bottom: 6px; }
    .counter-section { margin: 4px 0; }
    .counter-section ul { margin: 2px 0 0 16px; list-style: none; }
    .counter-unit { cursor: pointer; text-decoration: underline; }
    .counter-unit:hover { color: #88bbff; }
  `;

  private _toggle(index: number): void {
    this._expandedIndex = this._expandedIndex === index ? -1 : index;
  }

  private _selectUnit(unitType: string): void {
    if (typeof (window as any).selection?.set === 'function') {
      (window as any).selection.set({ type: 'unitType', unitType, isEnemy: false, source: 'workbench' });
    }
  }

  override render() {
    if (!this.data?.assessments?.length) {
      return html`<div>No pattern data</div>`;
    }
    return html`${this.data.assessments.map((ea, i) => this._renderAssessment(ea, i))}`;
  }

  private _renderAssessment(ea: EnrichedAssessment, i: number) {
    const a = ea.assessment;
    const conf = Math.round(a.confidence * 100);
    const barColor = conf > 70 ? '#44ff44' : conf > 50 ? '#ffaa00' : '#ff4444';
    const expanded = this._expandedIndex === i;
    const arrow = expanded ? '▼' : '▶';

    return html`
      <div class="assessment-item">
        <div class="assessment-header" @click=${() => this._toggle(i)}>${arrow} ${a.archetype} (${conf}%)</div>
        <div class="confidence-bar"><div class="bar-fill" style="width:${conf}%;background:${barColor}"></div></div>
        ${expanded ? html`
          <div class="assessment-body">
            <div class="rationale">${a.rationale ?? ''}</div>
            ${ea.counters ? this._renderCounters(ea.counters) : nothing}
          </div>
        ` : nothing}
      </div>
    `;
  }

  private _renderCounters(counters: CounterInfo) {
    return html`
      ${counters.strongCounters?.length ? html`
        <div class="counter-section"><strong>Strong Counters:</strong>
          <ul>${counters.strongCounters.map(c => html`
            <li>${c.units.map(u => html`<span class="counter-unit" @click=${() => this._selectUnit(u)}>${u}</span>`)} — ${c.action ?? ''}</li>
          `)}</ul>
        </div>
      ` : nothing}
      ${counters.weakCounters?.length ? html`
        <div class="counter-section"><strong>Weak Counters:</strong>
          <ul>${counters.weakCounters.map(c => html`
            <li>${c.units.map(u => html`<span class="counter-unit" @click=${() => this._selectUnit(u)}>${u}</span>`)} — ${c.action ?? ''}</li>
          `)}</ul>
        </div>
      ` : nothing}
    `;
  }
}
