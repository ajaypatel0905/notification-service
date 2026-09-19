# ADR 0006: API keys with two roles, tenant scope derived from the key

## Context
The roles are platform admin and tenant admin. OAuth/SSO/MFA are out of scope. Tenant isolation must not depend on
the client sending the right tenant id.

## Decision
`X-API-Key` header. Keys are random 192-bit values shown once and stored as SHA-256 hashes with a display prefix.
Each key carries a role and, for tenant admins, a tenant id. The tenant id used by every query comes from the
authenticated key, never from the request. Admin routes require `PLATFORM_ADMIN`; all other routes require
`TENANT_ADMIN`; vendor callbacks use a separate shared secret compared in constant time.

A bootstrap admin key is read from configuration so a fresh database is usable without a manual insert.

## Consequences
- Revocation is immediate (lookup per request, no cached sessions).
- No user identity below tenant admin. If tenants later need operator accounts, keys become one credential type
  among others; the `ApiPrincipal` seam stays.
