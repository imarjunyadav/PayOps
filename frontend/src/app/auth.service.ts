import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { tap } from 'rxjs';

interface AuthResponse { token: string; username: string; roles: string[]; merchantId?: number | null; tokenType: string; }

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly TOKEN_KEY = 'payops_token';
  private readonly USER_KEY  = 'payops_user';

  readonly currentUser = signal<{ username: string; roles: string[]; merchantId?: number | null } | null>(this.loadUser());

  login(username: string, password: string) {
    return this.http.post<AuthResponse>('/api/auth/login', { username, password }).pipe(
      tap(res => {
        try { localStorage.setItem(this.TOKEN_KEY, res.token); } catch { /* private window */ }
        const user = { username: res.username, roles: res.roles, merchantId: res.merchantId };
        try { localStorage.setItem(this.USER_KEY, JSON.stringify(user)); } catch { /* private window */ }
        this.currentUser.set(user);
      })
    );
  }

  logout(): void {
    try { localStorage.removeItem(this.TOKEN_KEY); localStorage.removeItem(this.USER_KEY); } catch { /* ignore */ }
    this.currentUser.set(null);
  }

  getToken(): string | null {
    try { return localStorage.getItem(this.TOKEN_KEY); } catch { return null; }
  }

  isLoggedIn(): boolean { return !!this.getToken(); }

  private loadUser(): { username: string; roles: string[]; merchantId?: number | null } | null {
    try {
      const raw = localStorage.getItem(this.USER_KEY);
      return raw ? JSON.parse(raw) : null;
    } catch { return null; }
  }
}
