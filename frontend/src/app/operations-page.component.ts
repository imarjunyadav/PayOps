import { CurrencyPipe, DatePipe, DecimalPipe, NgClass } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { catchError, finalize, forkJoin, of, timeout } from 'rxjs';

interface PaymentRow { id: number; reference: string; amount: number; currency: string; method: string; status: string; createdAt: string; failureReason?: string | null; maskedCardNumber?: string | null; merchant?: { id?: number; name?: string; code?: string }; switchDatabase?: { name?: string; code?: string }; }
interface PaymentEventRow { id: number; eventType: string; message?: string; occurredAt: string; }
interface SettlementFileRow { id: number; fileName: string; settlementDate: string; status: string; recordCount: number; totalAmount: number; switchDatabase?: { name?: string }; }
interface SettlementRecordRow { id: number; paymentReference: string; amount: number; fee: number; currency: string; transactionDate?: string; payment?: { id?: number } | null; settlementFile?: { id?: number }; }
interface ReconciliationRunRow { id: number; runNumber: string; status: string; startedAt: string; totalChecked: number; matchedCount: number; exceptionCount: number; settlementFile?: { fileName?: string }; }
interface ReconciliationResultRow { id: number; resultType: string; expectedAmount?: number | null; actualAmount?: number | null; note?: string; payment?: { id?: number; reference?: string } | null; settlementRecord?: { id?: number; paymentReference?: string } | null; reconciliationRun?: { id?: number; runNumber?: string }; }
interface MerchantRow { id: number; code: string; name: string; email?: string; currency: string; status: string; }
interface SwitchRow { id: number; code: string; name: string; switchType: string; protocol: string; status: string; priority: number; }
interface AccountRow { id: number; code: string; name: string; accountType: string; currency: string; active: boolean; merchant?: { name?: string }; }
interface PostingRow { id: number; direction: string; amount: number; description?: string; account?: { code?: string; name?: string }; journalEntry?: { id: number; entryNumber?: string; source?: string; description?: string; payment?: { id?: number; reference?: string } | null }; }
interface Journal { id: number; entryNumber: string; source: string; description: string; paymentId?: number; paymentRef?: string; lines: PostingRow[]; debit: number; credit: number; balanced: boolean; }

@Component({
  selector: 'epoc-operations-page',
  standalone: true,
  imports: [CurrencyPipe, DatePipe, DecimalPipe, FormsModule, NgClass],
  template: `
    <div class="page-heading">
      <div>
        <div class="eyebrow">{{ sectionLabel }}</div>
        <h1>{{ pageTitle }}</h1>
        <p class="page-subtitle">{{ pageSubtitle }}</p>
      </div>
      <div class="heading-actions">
        @if (loading) { <span class="loading-dots">Loading<span>...</span></span> }
        <span class="as-of">{{ today | date:'EEE, d MMM y' }}</span>
        <button class="button button--secondary" type="button" (click)="refresh()">
          <span class="refresh-mark" [class.refresh-mark--spinning]="loading">↻</span> Refresh
        </button>
      </div>
    </div>

    @if (errorMessage) {
      <div class="notice notice--danger" role="alert"><strong>Could not load data.</strong><span>{{ errorMessage }}</span></div>
    }
    @if (partialError && !errorMessage) {
      <div class="notice notice--warning" role="status"><strong>Some data failed to load.</strong><span>The API may be reconnecting — click Refresh to retry.</span></div>
    }

    @if (section === 'overview') {
      <section class="kpi-grid" aria-label="Operational summary">
        <article class="kpi-card">
          <div class="kpi-label">Total payments</div>
          <div class="kpi-value">{{ payments.length | number }}</div>
          <div class="kpi-note">Across {{ merchants.length }} merchant{{ merchants.length !== 1 ? 's' : '' }}</div>
        </article>
        <article class="kpi-card">
          <div class="kpi-label">Total volume</div>
          <div class="kpi-value">{{ volume | currency:'INR':'symbol':'1.0-0' }}</div>
          <div class="kpi-note">{{ approvedCount }} approved · {{ declinedCount }} declined</div>
        </article>
        <article class="kpi-card">
          <div class="kpi-label">Active merchants</div>
          <div class="kpi-value">{{ activeMerchantCount | number }}</div>
          <div class="kpi-note">{{ merchants.length - activeMerchantCount }} inactive</div>
        </article>
        <article class="kpi-card">
          <div class="kpi-label">Payment routes</div>
          <div class="kpi-value">{{ activeSwitchCount | number }}</div>
          <div class="kpi-note">of {{ switches.length }} route{{ switches.length !== 1 ? 's' : '' }} active</div>
        </article>
        <article class="kpi-card">
          <div class="kpi-label">Settlement files</div>
          <div class="kpi-value">{{ files.length | number }}</div>
          <div class="kpi-note">{{ settlementRecordTotal | number }} records received</div>
        </article>
        <article class="kpi-card">
          <div class="kpi-label">Reconciliation runs</div>
          <div class="kpi-value">{{ runs.length | number }}</div>
          <div class="kpi-note">{{ totalChecked | number }} items checked</div>
        </article>
        <article class="kpi-card">
          <div class="kpi-label">Match rate</div>
          <div class="kpi-value">{{ matchRate === null ? '—' : (matchRate | number:'1.0-1') + '%' }}</div>
          <div class="kpi-note">{{ exceptionCount | number }} exception{{ exceptionCount !== 1 ? 's' : '' }}</div>
        </article>
        <article class="kpi-card">
          <div class="kpi-label">Ledger balance</div>
          <div class="kpi-value">{{ balancedJournalCount }}/{{ journals.length }}</div>
          <div class="kpi-note">Journals balanced (Dr = Cr)</div>
        </article>
      </section>

      <div class="content-grid">
        <section class="panel">
          <div class="panel-heading"><div><h2>Payment breakdown</h2><p>Count and amount by status</p></div></div>
          <div class="bar-list">
            @for (row of paymentStatusSummary; track row.status) {
              <div class="bar-row">
                <span class="bar-name"><span class="badge" [ngClass]="badgeClass(row.status)">{{ statusLabel(row.status) }}</span></span>
                <span class="bar-track"><span class="bar-fill" [style.width.%]="row.pct"></span></span>
                <span class="bar-value">{{ row.count }} · {{ row.amount | currency:'INR':'symbol':'1.0-0' }}</span>
              </div>
            } @empty {
              <div class="empty-cell">No payments yet.</div>
            }
          </div>
        </section>
        <section class="panel">
          <div class="panel-heading"><div><h2>Reconciliation outcomes</h2><p>Results across all reconciliation runs</p></div></div>
          <div class="bar-list">
            @for (row of resultSummary; track row.type) {
              <div class="bar-row">
                <span class="bar-name"><span class="badge" [ngClass]="badgeClass(row.type)">{{ statusLabel(row.type) }}</span></span>
                <span class="bar-track"><span class="bar-fill" [ngClass]="row.type === 'MATCHED' ? '' : 'bar-fill--bad'" [style.width.%]="row.pct"></span></span>
                <span class="bar-value">{{ row.count }}</span>
              </div>
            } @empty {
              <div class="empty-cell">No reconciliation data yet.</div>
            }
          </div>
        </section>
      </div>

      <section class="panel">
        <div class="panel-heading">
          <div><h2>Recent payments</h2><p>Latest transactions — click Payments in the sidebar for full history</p></div>
          <a class="text-link" href="/payments" (click)="$event.preventDefault(); navigate('payments')">View all →</a>
        </div>
        <div class="table-wrap"><table>
          <thead><tr><th>Reference</th><th>Merchant</th><th>Route</th><th>Method</th><th>Amount</th><th>Status</th><th>Date</th></tr></thead>
          <tbody>
            @for (p of recentPayments; track p.id) {
              <tr>
                <td class="primary-cell mono">{{ p.reference }}</td>
                <td>{{ p.merchant?.name || '—' }}</td>
                <td>{{ p.switchDatabase?.name || '—' }}</td>
                <td>{{ p.method }}</td>
                <td class="amount-cell">{{ p.amount | currency:p.currency:'symbol':'1.2-2' }}</td>
                <td><span class="badge" [ngClass]="badgeClass(p.status)">{{ statusLabel(p.status) }}</span></td>
                <td class="muted-cell">{{ p.createdAt | date:'d MMM, HH:mm' }}</td>
              </tr>
            } @empty {
              <tr><td colspan="7" class="empty-cell">No payments yet. Submit a test payment via the API (Swagger at /swagger-ui.html).</td></tr>
            }
          </tbody>
        </table></div>
      </section>

      <div class="content-grid">
        <section class="panel">
          <div class="panel-heading">
            <div><h2>Settlement files</h2><p>Bank files received for matching</p></div>
            <a class="text-link" href="/settlements" (click)="$event.preventDefault(); navigate('settlements')">View all →</a>
          </div>
          <div class="table-wrap"><table>
            <thead><tr><th>File</th><th>Date</th><th>Records</th><th>Amount</th><th>Status</th></tr></thead>
            <tbody>
              @for (f of recentFiles; track f.id) {
                <tr>
                  <td class="primary-cell">{{ f.fileName }}</td>
                  <td class="muted-cell">{{ f.settlementDate | date:'d MMM y' }}</td>
                  <td>{{ f.recordCount | number }}</td>
                  <td class="amount-cell">{{ f.totalAmount | currency:'INR':'symbol':'1.0-0' }}</td>
                  <td><span class="badge" [ngClass]="badgeClass(f.status)">{{ statusLabel(f.status) }}</span></td>
                </tr>
              } @empty {
                <tr><td colspan="5" class="empty-cell">No settlement files uploaded yet.</td></tr>
              }
            </tbody>
          </table></div>
        </section>
        <section class="panel">
          <div class="panel-heading">
            <div><h2>Recent reconciliation</h2><p>Latest match results</p></div>
            <a class="text-link" href="/reconciliation" (click)="$event.preventDefault(); navigate('reconciliation')">View all →</a>
          </div>
          <div class="table-wrap"><table>
            <thead><tr><th>Reference</th><th>Expected</th><th>Actual</th><th>Result</th></tr></thead>
            <tbody>
              @for (r of recentResults; track r.id) {
                <tr>
                  <td class="primary-cell mono">{{ resultRef(r) }}</td>
                  <td class="amount-cell">{{ r.expectedAmount == null ? '—' : (r.expectedAmount | currency:'INR':'symbol':'1.0-0') }}</td>
                  <td class="amount-cell">{{ r.actualAmount == null ? '—' : (r.actualAmount | currency:'INR':'symbol':'1.0-0') }}</td>
                  <td><span class="badge" [ngClass]="badgeClass(r.resultType)">{{ statusLabel(r.resultType) }}</span></td>
                </tr>
              } @empty {
                <tr><td colspan="4" class="empty-cell">No reconciliation results yet.</td></tr>
              }
            </tbody>
          </table></div>
        </section>
      </div>
    }

    @if (section === 'payments') {
      <section class="panel">
        <div class="list-toolbar">
          <div><h2>All payments</h2><p>{{ filteredPayments.length }} of {{ payments.length }} record{{ payments.length !== 1 ? 's' : '' }} · click a row to see the full transaction trace</p></div>
          <div class="filters">
            <input class="search-input" type="search" placeholder="Search reference or merchant" [(ngModel)]="searchText" aria-label="Search payments">
            <select class="select-input" [(ngModel)]="statusFilter" aria-label="Filter by status">
              <option value="">All statuses</option>
              <option value="APPROVED">Approved</option>
              <option value="DECLINED">Declined</option>
              <option value="PENDING">Pending</option>
              <option value="INITIATED">Initiated</option>
              <option value="AUTHORIZED">Authorized</option>
              <option value="CAPTURED">Captured</option>
              <option value="FAILED">Failed</option>
              <option value="REFUNDED">Refunded</option>
              <option value="SETTLED">Settled</option>
            </select>
          </div>
        </div>
        <div class="table-wrap"><table>
          <thead><tr><th>Reference</th><th>Merchant</th><th>Route</th><th>Method</th><th>Amount</th><th>Status</th><th>Date</th></tr></thead>
          <tbody>
            @for (p of filteredPayments; track p.id) {
              <tr class="clickable" [class.selected]="selectedPaymentId === p.id" (click)="selectedPaymentId = selectedPaymentId === p.id ? null : p.id">
                <td class="primary-cell mono">{{ p.reference }}</td>
                <td>{{ p.merchant?.name || '—' }}</td>
                <td>{{ p.switchDatabase?.name || '—' }}</td>
                <td>{{ p.method }}</td>
                <td class="amount-cell">{{ p.amount | currency:p.currency:'symbol':'1.2-2' }}</td>
                <td><span class="badge" [ngClass]="badgeClass(p.status)">{{ statusLabel(p.status) }}</span></td>
                <td class="muted-cell">{{ p.createdAt | date:'d MMM y, HH:mm' }}</td>
              </tr>
            } @empty {
              <tr><td colspan="7" class="empty-cell">{{ payments.length ? 'No payments match the filter.' : 'No payments yet.' }}</td></tr>
            }
          </tbody>
        </table></div>
      </section>

      @if (selectedPayment; as sp) {
        <section class="panel">
          <div class="panel-heading">
            <div>
              <h2>Transaction trace — {{ sp.reference }}</h2>
              <p>End-to-end lifecycle: merchant → payment → events → ledger → settlement → reconciliation</p>
            </div>
            <button class="button button--ghost" type="button" (click)="selectedPaymentId = null">✕ Close</button>
          </div>
          <div class="trace">
            <div class="trace-step">
              <div class="trace-num">1</div>
              <div class="trace-body">
                <h3>Merchant</h3>
                <p><strong>{{ sp.merchant?.name || '—' }}</strong> <span class="muted">(code: {{ sp.merchant?.code || '—' }})</span></p>
              </div>
            </div>
            <div class="trace-step">
              <div class="trace-num">2</div>
              <div class="trace-body">
                <h3>Payment details</h3>
                <p>
                  {{ sp.amount | currency:sp.currency:'symbol':'1.2-2' }} via {{ sp.method }}
                  @if (sp.maskedCardNumber) { · card {{ sp.maskedCardNumber }} }
                  · routed via {{ sp.switchDatabase?.name || '—' }}
                  · <span class="badge" [ngClass]="badgeClass(sp.status)">{{ statusLabel(sp.status) }}</span>
                </p>
                @if (sp.failureReason) { <p class="muted">Decline reason: {{ sp.failureReason }}</p> }
              </div>
            </div>
            <div class="trace-step">
              <div class="trace-num">3</div>
              <div class="trace-body">
                <h3>Payment events</h3>
                @for (e of eventsFor(sp.id); track e.id) {
                  <p>
                    <span class="badge" [ngClass]="badgeClass(e.eventType)">{{ statusLabel(e.eventType) }}</span>
                    @if (e.message) { {{ e.message }} }
                    <span class="muted"> · {{ e.occurredAt | date:'d MMM, HH:mm:ss' }}</span>
                  </p>
                } @empty { <p class="muted">No events recorded for this payment.</p> }
              </div>
            </div>
            <div class="trace-step">
              <div class="trace-num">4</div>
              <div class="trace-body">
                <h3>Ledger entries (double-entry bookkeeping)</h3>
                @for (j of journalsForPayment(sp.id); track j.id) {
                  <p>
                    <strong class="mono">{{ j.entryNumber }}</strong> · {{ statusLabel(j.source) }}
                    · <span class="badge" [ngClass]="j.balanced ? 'badge--success' : 'badge--danger'">{{ j.balanced ? 'Balanced ✓' : 'Unbalanced ✗' }}</span>
                  </p>
                  @for (l of j.lines; track l.id) {
                    <p class="line">
                      {{ l.direction === 'DEBIT' ? 'Dr' : 'Cr' }} &nbsp;
                      <span class="mono">{{ l.account?.code }}</span> {{ l.account?.name }}
                      <span class="amount-inline">{{ l.amount | currency:'INR':'symbol':'1.2-2' }}</span>
                    </p>
                  }
                } @empty { <p class="muted">No ledger entry. Only approved payments generate ledger entries.</p> }
              </div>
            </div>
            <div class="trace-step">
              <div class="trace-num">5</div>
              <div class="trace-body">
                <h3>Settlement</h3>
                @for (r of recordsForPayment(sp); track r.id) {
                  <p>{{ fileName(r) }} · reported {{ r.amount | currency:'INR':'symbol':'1.2-2' }}, fee {{ r.fee | currency:'INR':'symbol':'1.2-2' }}</p>
                } @empty { <p class="muted">Not in any settlement file yet.</p> }
              </div>
            </div>
            <div class="trace-step">
              <div class="trace-num">6</div>
              <div class="trace-body">
                <h3>Reconciliation</h3>
                @for (r of resultsForPayment(sp.id); track r.id) {
                  <p>Run {{ r.reconciliationRun?.runNumber }} · <span class="badge" [ngClass]="badgeClass(r.resultType)">{{ statusLabel(r.resultType) }}</span></p>
                } @empty { <p class="muted">Not reconciled yet.</p> }
              </div>
            </div>
          </div>
        </section>
      }
    }

    @if (section === 'accounting') {
      <section class="panel">
        <div class="list-toolbar">
          <div>
            <h2>Journal entries</h2>
            <p>{{ journals.length }} entries · {{ balancedJournalCount }} balanced (Dr = Cr) · click a row to see its postings</p>
          </div>
        </div>
        <div class="table-wrap"><table>
          <thead><tr><th>Entry #</th><th>Source</th><th>Payment</th><th>Description</th><th>Debits</th><th>Credits</th><th>Balance</th></tr></thead>
          <tbody>
            @for (j of journals; track j.id) {
              <tr class="clickable" [class.selected]="selectedJournalId === j.id" (click)="selectedJournalId = selectedJournalId === j.id ? null : j.id">
                <td class="primary-cell mono">{{ j.entryNumber }}</td>
                <td>{{ statusLabel(j.source) }}</td>
                <td class="mono">{{ j.paymentRef || '—' }}</td>
                <td>{{ j.description }}</td>
                <td class="amount-cell">{{ j.debit | currency:'INR':'symbol':'1.2-2' }}</td>
                <td class="amount-cell">{{ j.credit | currency:'INR':'symbol':'1.2-2' }}</td>
                <td><span class="badge" [ngClass]="j.balanced ? 'badge--success' : 'badge--danger'">{{ j.balanced ? 'Balanced' : 'Unbalanced' }}</span></td>
              </tr>
            } @empty {
              <tr><td colspan="7" class="empty-cell">No journal entries yet. Approve a payment to create ledger entries.</td></tr>
            }
          </tbody>
        </table></div>
      </section>
      @if (selectedJournal; as sj) {
        <section class="panel">
          <div class="panel-heading">
            <div><h2>Postings — {{ sj.entryNumber }}</h2><p>Each row is one side of the double-entry. Debits must equal credits.</p></div>
            <button class="button button--ghost" type="button" (click)="selectedJournalId = null">✕ Close</button>
          </div>
          <div class="table-wrap"><table>
            <thead><tr><th>Account</th><th>Side</th><th>Debit</th><th>Credit</th><th>Memo</th></tr></thead>
            <tbody>
              @for (l of sj.lines; track l.id) {
                <tr>
                  <td class="primary-cell"><span class="mono">{{ l.account?.code }}</span> · {{ l.account?.name }}</td>
                  <td><span class="badge" [ngClass]="l.direction === 'DEBIT' ? 'badge--warning' : 'badge--neutral'">{{ l.direction }}</span></td>
                  <td class="amount-cell">{{ l.direction === 'DEBIT' ? (l.amount | currency:'INR':'symbol':'1.2-2') : '' }}</td>
                  <td class="amount-cell">{{ l.direction === 'CREDIT' ? (l.amount | currency:'INR':'symbol':'1.2-2') : '' }}</td>
                  <td class="muted-cell">{{ l.description || '—' }}</td>
                </tr>
              }
              <tr class="total-row">
                <td colspan="2">Totals</td>
                <td class="amount-cell">{{ sj.debit | currency:'INR':'symbol':'1.2-2' }}</td>
                <td class="amount-cell">{{ sj.credit | currency:'INR':'symbol':'1.2-2' }}</td>
                <td><span class="badge" [ngClass]="sj.balanced ? 'badge--success' : 'badge--danger'">{{ sj.balanced ? 'Balanced ✓' : 'Unbalanced ✗' }}</span></td>
              </tr>
            </tbody>
          </table></div>
        </section>
      }
    }

    @if (section === 'settlements') {
      <section class="panel">
        <div class="list-toolbar">
          <div><h2>Settlement files</h2><p>{{ files.length }} file{{ files.length !== 1 ? 's' : '' }} received from bank · click a file to see its records</p></div>
          <div class="filters">
            <input class="search-input" type="search" placeholder="Search file name" [(ngModel)]="searchText" aria-label="Search settlement files">
            <select class="select-input" [(ngModel)]="statusFilter" aria-label="Filter by status">
              <option value="">All statuses</option>
              <option value="RECEIVED">Received</option>
              <option value="PARSED">Parsed</option>
              <option value="RECONCILED">Reconciled</option>
              <option value="FAILED">Failed</option>
            </select>
          </div>
        </div>
        <div class="table-wrap"><table>
          <thead><tr><th>File name</th><th>Switch/Route</th><th>Settlement date</th><th>Records</th><th>Total amount</th><th>Status</th></tr></thead>
          <tbody>
            @for (f of filteredFiles; track f.id) {
              <tr class="clickable" [class.selected]="selectedFileId === f.id" (click)="selectedFileId = selectedFileId === f.id ? null : f.id">
                <td class="primary-cell">{{ f.fileName }}</td>
                <td>{{ f.switchDatabase?.name || '—' }}</td>
                <td class="muted-cell">{{ f.settlementDate | date:'d MMM y' }}</td>
                <td>{{ f.recordCount | number }}</td>
                <td class="amount-cell">{{ f.totalAmount | currency:'INR':'symbol':'1.2-2' }}</td>
                <td><span class="badge" [ngClass]="badgeClass(f.status)">{{ statusLabel(f.status) }}</span></td>
              </tr>
            } @empty {
              <tr><td colspan="6" class="empty-cell">No settlement files match.</td></tr>
            }
          </tbody>
        </table></div>
      </section>
      @if (selectedFile; as sf) {
        <section class="panel">
          <div class="panel-heading">
            <div><h2>Records — {{ sf.fileName }}</h2><p>Each row is one transaction the bank reported. "Linked" means it matches a payment in this system.</p></div>
            <button class="button button--ghost" type="button" (click)="selectedFileId = null">✕ Close</button>
          </div>
          <div class="table-wrap"><table>
            <thead><tr><th>Payment ref</th><th>Reported amount</th><th>Fee</th><th>Net</th><th>Transaction time</th><th>Match</th></tr></thead>
            <tbody>
              @for (r of recordsForFile(sf.id); track r.id) {
                <tr>
                  <td class="primary-cell mono">{{ r.paymentReference }}</td>
                  <td class="amount-cell">{{ r.amount | currency:'INR':'symbol':'1.2-2' }}</td>
                  <td class="amount-cell">{{ r.fee | currency:'INR':'symbol':'1.2-2' }}</td>
                  <td class="amount-cell">{{ r.amount - r.fee | currency:'INR':'symbol':'1.2-2' }}</td>
                  <td class="muted-cell">{{ r.transactionDate | date:'d MMM, HH:mm' }}</td>
                  <td><span class="badge" [ngClass]="r.payment ? 'badge--success' : 'badge--danger'">{{ r.payment ? 'Linked' : 'Unknown ref' }}</span></td>
                </tr>
              } @empty {
                <tr><td colspan="6" class="empty-cell">No records in this file.</td></tr>
              }
            </tbody>
          </table></div>
        </section>
      }
    }

    @if (section === 'reconciliation') {
      <section class="panel">
        <div class="list-toolbar">
          <div>
            <h2>Reconciliation runs</h2>
            <p>{{ runs.length }} run{{ runs.length !== 1 ? 's' : '' }} · overall match rate {{ matchRate === null ? '—' : (matchRate | number:'1.0-1') + '%' }} · click a run to see results</p>
          </div>
          <div class="filters">
            <input class="search-input" type="search" placeholder="Search run # or file" [(ngModel)]="searchText" aria-label="Search reconciliation runs">
            <select class="select-input" [(ngModel)]="statusFilter" aria-label="Filter by status">
              <option value="">All statuses</option>
              <option value="RUNNING">Running</option>
              <option value="COMPLETED">Completed</option>
              <option value="FAILED">Failed</option>
            </select>
          </div>
        </div>
        <div class="table-wrap"><table>
          <thead><tr><th>Run #</th><th>Settlement file</th><th>Started</th><th>Checked</th><th>Matched</th><th>Exceptions</th><th>Match rate</th><th>Status</th></tr></thead>
          <tbody>
            @for (r of filteredRuns; track r.id) {
              <tr class="clickable" [class.selected]="selectedRunId === r.id" (click)="selectedRunId = selectedRunId === r.id ? null : r.id">
                <td class="primary-cell mono">{{ r.runNumber }}</td>
                <td>{{ r.settlementFile?.fileName || '—' }}</td>
                <td class="muted-cell">{{ r.startedAt | date:'d MMM, HH:mm' }}</td>
                <td>{{ r.totalChecked | number }}</td>
                <td>{{ r.matchedCount | number }}</td>
                <td [class.exception-count]="r.exceptionCount > 0" [class.exception-count--clear]="r.exceptionCount === 0">{{ r.exceptionCount | number }}</td>
                <td>{{ r.totalChecked ? ((r.matchedCount / r.totalChecked * 100) | number:'1.0-1') + '%' : '—' }}</td>
                <td><span class="badge" [ngClass]="badgeClass(r.status)">{{ statusLabel(r.status) }}</span></td>
              </tr>
            } @empty {
              <tr><td colspan="8" class="empty-cell">No reconciliation runs. Upload a settlement file to start reconciliation.</td></tr>
            }
          </tbody>
        </table></div>
      </section>
      @if (selectedRun; as sr) {
        <section class="panel">
          <div class="panel-heading">
            <div><h2>Results — Run {{ sr.runNumber }}</h2><p>Payment records compared against the settlement file. Differences are flagged as exceptions.</p></div>
            <button class="button button--ghost" type="button" (click)="selectedRunId = null">✕ Close</button>
          </div>
          <div class="table-wrap"><table>
            <thead><tr><th>Payment ref</th><th>Expected (our records)</th><th>Actual (bank file)</th><th>Difference</th><th>Result</th><th>Note</th></tr></thead>
            <tbody>
              @for (r of resultsForRun(sr.id); track r.id) {
                <tr>
                  <td class="primary-cell mono">{{ resultRef(r) }}</td>
                  <td class="amount-cell">{{ r.expectedAmount == null ? '—' : (r.expectedAmount | currency:'INR':'symbol':'1.2-2') }}</td>
                  <td class="amount-cell">{{ r.actualAmount == null ? '—' : (r.actualAmount | currency:'INR':'symbol':'1.2-2') }}</td>
                  <td class="amount-cell">{{ (r.expectedAmount != null && r.actualAmount != null) ? ((r.actualAmount - r.expectedAmount) | currency:'INR':'symbol':'1.2-2') : '—' }}</td>
                  <td><span class="badge" [ngClass]="badgeClass(r.resultType)">{{ statusLabel(r.resultType) }}</span></td>
                  <td class="muted-cell">{{ r.note || '—' }}</td>
                </tr>
              } @empty {
                <tr><td colspan="6" class="empty-cell">No results recorded for this run.</td></tr>
              }
            </tbody>
          </table></div>
        </section>
      }
    }

    @if (section === 'merchants') {
      <section class="panel">
        <div class="list-toolbar">
          <div><h2>Merchants</h2><p>{{ merchants.length }} merchant{{ merchants.length !== 1 ? 's' : '' }} registered</p></div>
          <div class="filters">
            <input class="search-input" type="search" placeholder="Search code, name or email" [(ngModel)]="searchText" aria-label="Search merchants">
          </div>
        </div>
        <div class="table-wrap"><table>
          <thead><tr><th>Code</th><th>Name</th><th>Email</th><th>Currency</th><th>Status</th></tr></thead>
          <tbody>
            @for (m of filteredMerchants; track m.id) {
              <tr>
                <td class="primary-cell mono">{{ m.code }}</td>
                <td>{{ m.name }}</td>
                <td class="muted-cell">{{ m.email || '—' }}</td>
                <td>{{ m.currency }}</td>
                <td><span class="badge" [ngClass]="badgeClass(m.status)">{{ statusLabel(m.status) }}</span></td>
              </tr>
            } @empty {
              <tr><td colspan="5" class="empty-cell">No merchants found.</td></tr>
            }
          </tbody>
        </table></div>
      </section>
    }

    @if (section === 'switches') {
      <section class="panel">
        <div class="list-toolbar">
          <div><h2>Payment routes</h2><p>{{ switches.length }} bank connection{{ switches.length !== 1 ? 's' : '' }} configured — routes payments from merchants to the bank</p></div>
          <div class="filters">
            <input class="search-input" type="search" placeholder="Search code, name or type" [(ngModel)]="searchText" aria-label="Search routes">
          </div>
        </div>
        <div class="table-wrap"><table>
          <thead><tr><th>Code</th><th>Name</th><th>Type</th><th>Protocol</th><th>Priority</th><th>Status</th></tr></thead>
          <tbody>
            @for (s of filteredSwitches; track s.id) {
              <tr>
                <td class="primary-cell mono">{{ s.code }}</td>
                <td>{{ s.name }}</td>
                <td>{{ statusLabel(s.switchType) }}</td>
                <td>{{ s.protocol }}</td>
                <td>{{ s.priority }}</td>
                <td><span class="badge" [ngClass]="badgeClass(s.status)">{{ statusLabel(s.status) }}</span></td>
              </tr>
            } @empty {
              <tr><td colspan="6" class="empty-cell">No payment routes configured.</td></tr>
            }
          </tbody>
        </table></div>
      </section>
    }

    @if (section === 'accounts') {
      <section class="panel">
        <div class="list-toolbar">
          <div><h2>Chart of accounts</h2><p>{{ accounts.length }} ledger account{{ accounts.length !== 1 ? 's' : '' }} — used to record debits and credits</p></div>
          <div class="filters">
            <input class="search-input" type="search" placeholder="Search code, name or type" [(ngModel)]="searchText" aria-label="Search accounts">
          </div>
        </div>
        <div class="table-wrap"><table>
          <thead><tr><th>Code</th><th>Account name</th><th>Type</th><th>Currency</th><th>Merchant</th><th>Status</th></tr></thead>
          <tbody>
            @for (a of filteredAccounts; track a.id) {
              <tr>
                <td class="primary-cell mono">{{ a.code }}</td>
                <td>{{ a.name }}</td>
                <td>{{ statusLabel(a.accountType) }}</td>
                <td>{{ a.currency }}</td>
                <td>{{ a.merchant?.name || 'System' }}</td>
                <td><span class="badge" [ngClass]="a.active ? 'badge--success' : 'badge--neutral'">{{ a.active ? 'Active' : 'Inactive' }}</span></td>
              </tr>
            } @empty {
              <tr><td colspan="6" class="empty-cell">No accounts configured.</td></tr>
            }
          </tbody>
        </table></div>
      </section>
    }

    <footer class="page-footer">EPOC <span>·</span> Educational payment operations simulator</footer>
  `
})
export class OperationsPageComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  private static cache: {
    payments: PaymentRow[]; files: SettlementFileRow[]; runs: ReconciliationRunRow[];
    merchants: MerchantRow[]; switches: SwitchRow[]; accounts: AccountRow[];
    postings: PostingRow[]; journals: Journal[];
    events: Map<number, PaymentEventRow[]>; records: SettlementRecordRow[]; results: ReconciliationResultRow[];
    at: number;
  } | null = null;

  section = 'overview';
  today = new Date();
  loading = false;
  errorMessage = '';
  partialError = false;
  searchText = '';
  statusFilter = '';

  payments: PaymentRow[] = [];
  files: SettlementFileRow[] = [];
  runs: ReconciliationRunRow[] = [];
  merchants: MerchantRow[] = [];
  switches: SwitchRow[] = [];
  accounts: AccountRow[] = [];
  postings: PostingRow[] = [];
  records: SettlementRecordRow[] = [];
  results: ReconciliationResultRow[] = [];
  events = new Map<number, PaymentEventRow[]>();
  journals: Journal[] = [];

  selectedPaymentId: number | null = null;
  selectedJournalId: number | null = null;
  selectedFileId: number | null = null;
  selectedRunId: number | null = null;

  ngOnInit(): void {
    this.route.data.subscribe(data => {
      this.section = String(data['section'] ?? 'overview');
      this.searchText = '';
      this.statusFilter = '';
    });
    const c = OperationsPageComponent.cache;
    if (c && Date.now() - c.at < 60_000) {
      this.payments = c.payments; this.files = c.files; this.runs = c.runs;
      this.merchants = c.merchants; this.switches = c.switches; this.accounts = c.accounts;
      this.postings = c.postings; this.journals = c.journals;
      this.events = c.events; this.records = c.records; this.results = c.results;
    } else {
      this.refresh();
    }
  }

  refresh(): void {
    this.loading = true;
    this.errorMessage = '';
    this.partialError = false;
    let pending = 7;

    const done = () => {
      pending--;
      if (pending === 0) {
        this.loading = false;
        OperationsPageComponent.cache = {
          payments: this.payments, files: this.files, runs: this.runs,
          merchants: this.merchants, switches: this.switches, accounts: this.accounts,
          postings: this.postings, journals: this.journals,
          events: this.events, records: this.records, results: this.results,
          at: Date.now(),
        };
        this.loadDetails();
      }
    };

    const safe = <T>(url: string, setter: (d: T[]) => void) => {
      this.http.get<T[]>(url).pipe(
        timeout(15000),
        catchError(() => { this.partialError = true; return of([] as T[]); }),
        finalize(done),
      ).subscribe({
        next: d => { try { setter(d); } catch (_) {} },
      });
    };

    safe<PaymentRow>('/api/payments',              d => this.payments  = d);
    safe<SettlementFileRow>('/api/settlement-files', d => this.files     = d);
    safe<ReconciliationRunRow>('/api/reconciliation-runs', d => this.runs = d);
    safe<MerchantRow>('/api/merchants',            d => this.merchants = d);
    safe<SwitchRow>('/api/switches',               d => this.switches  = d);
    safe<AccountRow>('/api/accounts',              d => this.accounts  = d);
    safe<PostingRow>('/api/postings',              d => { this.postings = d; try { this.journals = this.buildJournals(d); } catch (_) { this.journals = []; } });
  }

  private loadDetails(): void {
    if (this.payments.length === 0 && this.files.length === 0 && this.runs.length === 0) return;

    const safeChildGet = <T>(url: string) =>
      this.http.get<T[]>(url).pipe(timeout(8000), catchError(() => of([] as T[])));

    forkJoin({
      events:  this.payments.length ? forkJoin(this.payments.map(p => safeChildGet<PaymentEventRow>(`/api/payments/${p.id}/events`))) : of([] as PaymentEventRow[][]),
      records: this.files.length    ? forkJoin(this.files.map(f => safeChildGet<SettlementRecordRow>(`/api/settlement-files/${f.id}/records`))) : of([] as SettlementRecordRow[][]),
      results: this.runs.length     ? forkJoin(this.runs.map(r => safeChildGet<ReconciliationResultRow>(`/api/reconciliation-runs/${r.id}/results`))) : of([] as ReconciliationResultRow[][]),
    }).subscribe({
      next: d => {
        try {
          const evList = d.events as PaymentEventRow[][];
          const recList = d.records as SettlementRecordRow[][];
          const resList = d.results as ReconciliationResultRow[][];
          this.events  = new Map(this.payments.map((p, i) => [p.id, evList[i] ?? []]));
          this.records = recList.flatMap((list, i) => (list ?? []).map(r => ({ ...r, settlementFile: r.settlementFile ?? { id: this.files[i]?.id } })));
          this.results = resList.flatMap((list, i) => (list ?? []).map(r => ({ ...r, reconciliationRun: r.reconciliationRun ?? { id: this.runs[i]?.id, runNumber: this.runs[i]?.runNumber } })));
          if (OperationsPageComponent.cache) {
            OperationsPageComponent.cache.events = this.events;
            OperationsPageComponent.cache.records = this.records;
            OperationsPageComponent.cache.results = this.results;
          }
        } catch (_) { /* detail loading failed silently */ }
      },
      error: () => { /* detail loading failed */ }
    });
  }

  private buildJournals(postings: PostingRow[]): Journal[] {
    const map = new Map<number, Journal>();
    for (const p of postings) {
      const je = p.journalEntry;
      if (!je) continue;
      let j = map.get(je.id);
      if (!j) {
        j = { id: je.id, entryNumber: je.entryNumber ?? `#${je.id}`, source: je.source ?? '', description: je.description ?? '', paymentId: je.payment?.id, paymentRef: je.payment?.reference, lines: [], debit: 0, credit: 0, balanced: false };
        map.set(je.id, j);
      }
      j.lines.push(p);
      if (p.direction === 'DEBIT') j.debit += Number(p.amount); else j.credit += Number(p.amount);
    }
    const list = [...map.values()].sort((a, b) => a.id - b.id);
    for (const j of list) j.balanced = Math.abs(j.debit - j.credit) < 0.005;
    return list;
  }

  // ── Page metadata ────────────────────────────────────────────────────────
  get pageTitle(): string {
    return ({ overview: 'Operations overview', payments: 'Payments', accounting: 'Ledger', settlements: 'Settlement files', reconciliation: 'Reconciliation', merchants: 'Merchants', switches: 'Payment routes', accounts: 'Chart of accounts' } as Record<string, string>)[this.section] ?? 'Operations';
  }
  get sectionLabel(): string { return this.section === 'overview' ? 'OPERATIONS' : 'WORKSPACE'; }
  get pageSubtitle(): string {
    return ({
      overview:       'Payment processing, ledger, and reconciliation at a glance.',
      payments:       'All transactions through this switch. Click a row to trace it end to end.',
      accounting:     'Double-entry journal entries. Each approved payment creates a balanced Dr/Cr pair.',
      settlements:    'Bank settlement files received for matching against payment records.',
      reconciliation: 'Payment records compared against bank settlement files. Exceptions are flagged for review.',
      merchants:      'Merchants registered to accept payments through this system.',
      switches:       'Bank connections (payment routes) that process transactions.',
      accounts:       'Ledger accounts used to record debits and credits (double-entry bookkeeping).',
    } as Record<string, string>)[this.section] ?? '';
  }

  // ── KPIs ─────────────────────────────────────────────────────────────────
  get volume(): number { return this.payments.reduce((s, p) => s + Number(p.amount || 0), 0); }
  get approvedCount(): number { return this.payments.filter(p => p.status === 'APPROVED').length; }
  get declinedCount(): number { return this.payments.filter(p => p.status === 'DECLINED').length; }
  get activeMerchantCount(): number { return this.merchants.filter(m => m.status === 'ACTIVE').length; }
  get activeSwitchCount(): number { return this.switches.filter(s => s.status === 'ACTIVE').length; }
  get settlementRecordTotal(): number { return this.files.reduce((s, f) => s + (f.recordCount || 0), 0); }
  get totalChecked(): number { return this.runs.reduce((s, r) => s + (r.totalChecked || 0), 0); }
  get exceptionCount(): number { return this.runs.reduce((s, r) => s + (r.exceptionCount || 0), 0); }
  get matchRate(): number | null {
    const checked = this.totalChecked;
    return checked ? this.runs.reduce((s, r) => s + (r.matchedCount || 0), 0) / checked * 100 : null;
  }
  get balancedJournalCount(): number { return this.journals.filter(j => j.balanced).length; }

  get paymentStatusSummary(): { status: string; count: number; amount: number; pct: number }[] {
    const m = new Map<string, { count: number; amount: number }>();
    for (const p of this.payments) {
      const e = m.get(p.status) ?? { count: 0, amount: 0 };
      e.count++;
      e.amount += Number(p.amount || 0);
      m.set(p.status, e);
    }
    return [...m.entries()].map(([status, v]) => ({ status, ...v, pct: this.payments.length ? v.count / this.payments.length * 100 : 0 }));
  }
  get resultSummary(): { type: string; count: number; pct: number }[] {
    const m = new Map<string, number>();
    for (const r of this.results) m.set(r.resultType, (m.get(r.resultType) ?? 0) + 1);
    return [...m.entries()].map(([type, count]) => ({ type, count, pct: this.results.length ? count / this.results.length * 100 : 0 }));
  }

  get recentPayments(): PaymentRow[] { return [...this.payments].sort((a, b) => this.time(b.createdAt) - this.time(a.createdAt) || b.id - a.id).slice(0, 6); }
  get recentFiles(): SettlementFileRow[] { return [...this.files].sort((a, b) => this.time(b.settlementDate) - this.time(a.settlementDate) || b.id - a.id).slice(0, 5); }
  get recentResults(): ReconciliationResultRow[] { return [...this.results].sort((a, b) => b.id - a.id).slice(0, 6); }

  // ── Selections ────────────────────────────────────────────────────────────
  get selectedPayment(): PaymentRow | undefined { return this.payments.find(p => p.id === this.selectedPaymentId); }
  get selectedJournal(): Journal | undefined { return this.journals.find(j => j.id === this.selectedJournalId); }
  get selectedFile(): SettlementFileRow | undefined { return this.files.find(f => f.id === this.selectedFileId); }
  get selectedRun(): ReconciliationRunRow | undefined { return this.runs.find(r => r.id === this.selectedRunId); }

  eventsFor(paymentId: number): PaymentEventRow[] { return this.events.get(paymentId) ?? []; }
  journalsForPayment(paymentId: number): Journal[] { return this.journals.filter(j => j.paymentId === paymentId); }
  recordsForPayment(p: PaymentRow): SettlementRecordRow[] { return this.records.filter(r => r.payment?.id === p.id || (!r.payment && r.paymentReference === p.reference)); }
  recordsForFile(fileId: number): SettlementRecordRow[] { return this.records.filter(r => r.settlementFile?.id === fileId); }
  resultsForPayment(paymentId: number): ReconciliationResultRow[] { return this.results.filter(r => r.payment?.id === paymentId); }
  resultsForRun(runId: number): ReconciliationResultRow[] { return this.results.filter(r => r.reconciliationRun?.id === runId); }
  fileName(r: SettlementRecordRow): string { return this.files.find(f => f.id === r.settlementFile?.id)?.fileName ?? 'Settlement file'; }
  resultRef(r: ReconciliationResultRow): string {
    if (r.payment?.reference) return r.payment.reference;
    if (r.settlementRecord?.paymentReference) return r.settlementRecord.paymentReference;
    const p = this.payments.find(x => x.id === r.payment?.id);
    const s = this.records.find(x => x.id === r.settlementRecord?.id);
    return p?.reference ?? s?.paymentReference ?? '—';
  }

  // ── Filters ───────────────────────────────────────────────────────────────
  get filteredPayments(): PaymentRow[] {
    const q = this.searchText.toLowerCase().trim();
    return this.payments.filter(i => (!this.statusFilter || i.status === this.statusFilter) && (!q || `${i.reference} ${i.merchant?.name ?? ''}`.toLowerCase().includes(q)));
  }
  get filteredFiles(): SettlementFileRow[] {
    const q = this.searchText.toLowerCase().trim();
    return this.files.filter(i => (!this.statusFilter || i.status === this.statusFilter) && (!q || i.fileName.toLowerCase().includes(q)));
  }
  get filteredRuns(): ReconciliationRunRow[] {
    const q = this.searchText.toLowerCase().trim();
    return this.runs.filter(i => (!this.statusFilter || i.status === this.statusFilter) && (!q || `${i.runNumber} ${i.settlementFile?.fileName ?? ''}`.toLowerCase().includes(q)));
  }
  get filteredMerchants(): MerchantRow[] {
    const q = this.searchText.toLowerCase().trim();
    return this.merchants.filter(i => !q || `${i.code} ${i.name} ${i.email ?? ''}`.toLowerCase().includes(q));
  }
  get filteredSwitches(): SwitchRow[] {
    const q = this.searchText.toLowerCase().trim();
    return this.switches.filter(i => !q || `${i.code} ${i.name} ${i.switchType}`.toLowerCase().includes(q));
  }
  get filteredAccounts(): AccountRow[] {
    const q = this.searchText.toLowerCase().trim();
    return this.accounts.filter(i => !q || `${i.code} ${i.name} ${i.accountType}`.toLowerCase().includes(q));
  }

  // ── Display helpers ───────────────────────────────────────────────────────
  statusLabel(value: string | null | undefined): string {
    const map: Record<string, string> = {
      APPROVED: 'Approved', DECLINED: 'Declined', PENDING: 'Pending',
      INITIATED: 'Initiated', AUTHORIZED: 'Authorized', CAPTURED: 'Captured',
      FAILED: 'Failed', REFUNDED: 'Refunded', SETTLED: 'Settled',
      ACTIVE: 'Active', INACTIVE: 'Inactive', SUSPENDED: 'Suspended',
      RECEIVED: 'Received', PARSED: 'Parsed', RECONCILED: 'Reconciled',
      RUNNING: 'Running', COMPLETED: 'Completed',
      MATCHED: 'Matched', AMOUNT_MISMATCH: 'Amt mismatch', MISSING_IN_SETTLEMENT: 'Missing in bank',
      MISSING_IN_PAYMENTS: 'Missing in our records', DUPLICATE: 'Duplicate',
      DEBIT: 'Debit', CREDIT: 'Credit',
      ASSET: 'Asset', LIABILITY: 'Liability', EQUITY: 'Equity', REVENUE: 'Revenue', EXPENSE: 'Expense',
      BANK: 'Bank', PAYMENT_SWITCH: 'Switch', REST: 'REST', ISO8583: 'ISO8583',
      PAYMENT_APPROVED: 'Approved ledger', PAYMENT_REFUND: 'Refund ledger',
    };
    return map[value ?? ''] ?? (value ?? 'Unknown').replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
  }

  badgeClass(value: string | null | undefined): string {
    const v = (value ?? '').toUpperCase();
    if (['ACTIVE', 'APPROVED', 'CAPTURED', 'SETTLED', 'RECONCILED', 'COMPLETED', 'MATCHED'].includes(v)) return 'badge--success';
    if (['FAILED', 'DECLINED', 'SUSPENDED', 'CLOSED', 'AMOUNT_MISMATCH', 'MISSING_IN_SETTLEMENT', 'MISSING_IN_PAYMENTS', 'DUPLICATE'].includes(v)) return 'badge--danger';
    if (['PENDING', 'RECEIVED', 'PARSED', 'RUNNING', 'AUTHORIZED', 'MAINTENANCE', 'INITIATED'].includes(v)) return 'badge--warning';
    return 'badge--neutral';
  }

  navigate(section: string): void { void this.router.navigate(['/', section]); }

  private time(value: string | null | undefined): number {
    if (!value) return 0;
    const t = new Date(value).getTime();
    return Number.isNaN(t) ? 0 : t;
  }
}
