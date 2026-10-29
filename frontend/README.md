# EPOC frontend

Angular 22 interface for the PayOps operations API. It provides an operational overview and searchable tables for payments, settlement files, reconciliation runs, merchants, switch connections, and accounts.

## Run locally

Angular 22 supports Node.js `^22.22.3`, `^24.15.0`, or `^26.0.0`. Node.js 25 is outside the supported ranges. Install a supported Node.js release, then from this directory run:

```powershell
npm install
npm start
```

The dev server runs at `http://localhost:4200`. Requests to `/api` are proxied to the Spring Boot server at `http://localhost:8080` by `proxy.conf.json`. Start the backend separately with its PostgreSQL environment configured.
