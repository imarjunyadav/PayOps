import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet, Router } from '@angular/router';
import { AuthService } from './auth.service';

@Component({
  selector: 'epoc-root',
  standalone: true,
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  template: `
    @if (auth.isLoggedIn()) {
      <div class="shell">
        <aside class="sidebar">
          <a class="brand" routerLink="/overview" aria-label="EPOC home">
            <span class="brand-mark">E</span>
            <span class="brand-copy"><strong>EPOC</strong><small>PAYMENT OPERATIONS</small></span>
          </a>

          <div class="nav-label">WORKSPACE</div>
          <nav class="nav-list" aria-label="Main navigation">
            <a routerLink="/overview"       routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }"><span class="nav-glyph">OV</span>Overview</a>
            <a routerLink="/payments"       routerLinkActive="active"><span class="nav-glyph">PY</span>Payments</a>
            <a routerLink="/accounting"     routerLinkActive="active"><span class="nav-glyph">AC</span>Accounting</a>
            <a routerLink="/settlements"    routerLinkActive="active"><span class="nav-glyph">ST</span>Settlement files</a>
            <a routerLink="/reconciliation" routerLinkActive="active"><span class="nav-glyph">RC</span>Reconciliation</a>
          </nav>

          <div class="nav-label nav-label--spaced">REFERENCE DATA</div>
          <nav class="nav-list" aria-label="Reference data navigation">
            <a routerLink="/merchants"  routerLinkActive="active"><span class="nav-glyph">ME</span>Merchants</a>
            <a routerLink="/switches"   routerLinkActive="active"><span class="nav-glyph">SW</span>Switches</a>
            <a routerLink="/accounts"   routerLinkActive="active"><span class="nav-glyph">GL</span>Accounts</a>
          </nav>

          <div class="sidebar-bottom">
            <div class="environment"><span class="environment-dot"></span><span><strong>Development</strong><small>LOCAL ENVIRONMENT</small></span></div>
            <div class="sidebar-user">
              <span class="sidebar-username">{{ auth.currentUser()?.username }}</span>
              <span class="sidebar-role">{{ auth.currentUser()?.roles?.[0] }}</span>
              <button class="button button--ghost sidebar-logout" type="button" (click)="logout()">Sign out</button>
            </div>
          </div>
        </aside>

        <div class="main-shell">
          <header class="topbar">
            <div class="topbar-context"><span class="topbar-kicker">PAYOPS</span><span class="topbar-divider">/</span><span>Operations Center</span></div>
            <div class="topbar-right"><span class="local-tag">LOCAL</span></div>
          </header>
          <main class="workspace"><router-outlet /></main>
        </div>
      </div>
    } @else {
      <router-outlet />
    }
  `
})
export class AppComponent {
  readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  logout(): void {
    this.auth.logout();
    void this.router.navigate(['/login']);
  }
}
