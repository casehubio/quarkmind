import { LitElement, html, css, nothing } from 'lit';
import { customElement, property } from 'lit/decorators.js';

interface CommentaryEntry {
  id: string;
  sender: string;
  content: string;
  topic: string;
  createdAt: string;
  timingFeedback?: boolean;
  accuracyFeedback?: boolean;
}

@customElement('qm-commentary-page')
export class QmCommentaryPage extends LitElement {
  @property({ attribute: false }) messages: CommentaryEntry[] = [];

  static override styles = css`
    :host { display: block; padding: 10px; font-size: 12px; color: #ccc; overflow-y: auto; height: 100%; }
    .entry { border-bottom: 1px solid #1a1a3e; padding: 6px 0; }
    .entry-header { font-size: 11px; color: #88bbff; }
    .entry-text { margin: 4px 0; line-height: 1.4; }
    .feedback { display: flex; gap: 4px; margin-top: 4px; }
    .fb-btn { cursor: pointer; padding: 1px 6px; border: 1px solid #444; background: #1a1a2e; color: #aaa; border-radius: 3px; font-size: 11px; }
    .fb-btn:hover:not(:disabled) { background: #2a2a4e; color: #ddd; }
    .fb-btn:disabled { opacity: 0.4; cursor: default; }
    .fb-btn.good:hover:not(:disabled) { border-color: #4a4; }
    .fb-btn.bad:hover:not(:disabled) { border-color: #a44; }
  `;

  private _sendFeedback(entry: CommentaryEntry, dimension: string, positive: boolean) {
    this.dispatchEvent(new CustomEvent('commentary-feedback', {
      bubbles: true, composed: true,
      detail: { workerId: entry.sender, dimension, positive },
    }));
    if (dimension === 'timing-quality') entry.timingFeedback = true;
    if (dimension === 'accuracy') entry.accuracyFeedback = true;
    this.requestUpdate();
  }

  private _formatSender(sender: string): string {
    const short = sender.replace(/^commentator-/, '');
    return short.charAt(0).toUpperCase() + short.slice(1);
  }

  override render() {
    if (!this.messages.length) {
      return html`<div style="color:#888;">Waiting for commentary — requires a configured LLM provider</div>`;
    }
    return html`${this.messages.map(m => this._renderEntry(m))}`;
  }

  private _renderEntry(m: CommentaryEntry) {
    return html`
      <div class="entry">
        <div class="entry-header">${this._formatSender(m.sender)} · ${m.topic ?? ''}</div>
        <div class="entry-text">${m.content}</div>
        <div class="feedback">
          <button class="fb-btn good" ?disabled=${m.timingFeedback} @click=${() => this._sendFeedback(m, 'timing-quality', true)}>👍 Timing</button>
          <button class="fb-btn bad" ?disabled=${m.timingFeedback} @click=${() => this._sendFeedback(m, 'timing-quality', false)}>👎 Timing</button>
          <button class="fb-btn good" ?disabled=${m.accuracyFeedback} @click=${() => this._sendFeedback(m, 'accuracy', true)}>✓ Accurate</button>
          <button class="fb-btn bad" ?disabled=${m.accuracyFeedback} @click=${() => this._sendFeedback(m, 'accuracy', false)}>✗ Wrong</button>
        </div>
      </div>
    `;
  }
}
