import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from './auth.service';

@Component({
  selector: 'epoc-login',
  standalone: true,
  imports: [FormsModule],
  template: `
    <div class="login-shell">
      <div class="login-card">
        <div class="login-brand">
          <span class="brand-mark">E</span>
          <span class="brand-copy"><strong>EPOC</strong><small>PAYMENT OPERATIONS</small></span>
        </div>
        <h1 class="login-title">Sign in</h1>
        <p class="login-sub">PayOps Operations Portal</p>

        @if (error()) {
          <div class="notice notice--warning" role="alert"><strong>Sign-in failed</strong><span>{{ error() }}</span></div>
        }

        <form (ngSubmit)="submit()" #f="ngForm">
          <div class="field">
            <label for="username">Username</label>
            <input id="username" type="text" name="username" [(ngModel)]="username" required autocomplete="username" placeholder="admin" [disabled]="loading()">
          </div>
          <div class="field">
            <label for="password">Password</label>
            <input id="password" type="password" name="password" [(ngModel)]="password" required autocomplete="current-password" placeholder="••••••••" [disabled]="loading()">
          </div>
          <button class="button button--primary login-btn" type="submit" [disabled]="loading() || !f.valid">
            {{ loading() ? 'Signing in…' : 'Sign in' }}
          </button>
        </form>

        <p class="login-hint">Demo: <code>admin / Admin@123!</code> &nbsp;·&nbsp; <code>merchant1 / Merchant@123!</code></p>
      </div>
    </div>
  `
})
export class LoginComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  username = '';
  password = '';
  loading = signal(false);
  error = signal('');

  submit(): void {
    this.loading.set(true);
    this.error.set('');
    this.auth.login(this.username, this.password).subscribe({
      next: () => { void this.router.navigate(['/overview']); },
      error: (err) => {
        this.error.set(err?.error?.message ?? 'Invalid username or password.');
        this.loading.set(false);
      }
    });
  }
}
