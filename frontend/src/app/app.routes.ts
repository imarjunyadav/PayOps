import { Routes } from '@angular/router';
import { LoginComponent } from './login.component';
import { OperationsPageComponent } from './operations-page.component';
import { authGuard } from './auth.guard';

export const routes: Routes = [
  { path: 'login', component: LoginComponent },
  { path: '', pathMatch: 'full', redirectTo: 'overview' },
  { path: 'overview',        component: OperationsPageComponent, data: { section: 'overview' },        canActivate: [authGuard] },
  { path: 'payments',        component: OperationsPageComponent, data: { section: 'payments' },        canActivate: [authGuard] },
  { path: 'accounting',      component: OperationsPageComponent, data: { section: 'accounting' },      canActivate: [authGuard] },
  { path: 'settlements',     component: OperationsPageComponent, data: { section: 'settlements' },     canActivate: [authGuard] },
  { path: 'reconciliation',  component: OperationsPageComponent, data: { section: 'reconciliation' },  canActivate: [authGuard] },
  { path: 'merchants',       component: OperationsPageComponent, data: { section: 'merchants' },       canActivate: [authGuard] },
  { path: 'switches',        component: OperationsPageComponent, data: { section: 'switches' },        canActivate: [authGuard] },
  { path: 'accounts',        component: OperationsPageComponent, data: { section: 'accounts' },        canActivate: [authGuard] },
  { path: '**', redirectTo: 'overview' }
];
