# WRMS External API — Authentication & Security Guide

This document describes how authentication and authorization are handled in the WRMS External API.

---

## 1. Authentication Mechanisms

The External API supports two standard HTTP header methods:

### Method A: Custom Header (Recommended)
```http
X-API-Key: wrms_live_a1b2c3d4e5f6789012345678abcdef01
```

### Method B: Authorization Bearer Header
```http
Authorization: Bearer wrms_live_a1b2c3d4e5f6789012345678abcdef01
```

---

## 2. API Key Format & Lifecycle

1. **Format**: Keys begin with `wrms_live_` followed by 32 cryptographically random hexadecimal characters (e.g. `wrms_live_9f81a7b6c5d4e3f2109876543210abcd`).
2. **One-Time Display**: When an administrator provisions a new API key via `/api/admin/external-clients`, the unmasked key is shown **only once** in the response.
3. **One-Way Hashing**: WRMS hashes the key using **SHA-256** before persisting it to the database (`master_reference_data` table). The plain API key is never stored in any database table, log file, or console output.

---

## 3. Scopes & Granular Permissions

Each API key is assigned one or more scopes:

| Scope | Endpoints Allowed |
|---|---|
| `ROSTER_READ` | `/api/external/v1/rosters/**` |
| `EMPLOYEE_READ` | `/api/external/v1/employees/**` |
| `SHIFT_READ` | `/api/external/v1/shifts/**` |
| `LEAVE_READ` | `/api/external/v1/leaves/**` |

* If an API client has `ROSTER_READ` and attempts to call `/api/external/v1/employees`, the API responds with:
  ```json
  {
    "success": false,
    "error": {
      "code": "FORBIDDEN",
      "message": "Insufficient permissions. Client lacks the required scope for this endpoint."
    }
  }
  ```

---

## 4. Key Management & Revocation

Administrators can instantly disable or delete any compromised key:
* **Disable/Enable**: `PUT /api/admin/external-clients/{id}/toggle`
* **Revoke**: `DELETE /api/admin/external-clients/{id}`

Once disabled, any subsequent request with that key immediately receives a `401 Unauthorized` response.

---

## 5. Security Rules for Integrators

> [!IMPORTANT]
> - **Never embed API keys into client-side code** (HTML, front-end JavaScript, mobile APKs). External APIs are intended for server-to-server calls.
> - **Always use HTTPS** in production.
> - **Rotate keys periodically**.
> - **Store keys in environment variables** or secret management vaults (e.g. AWS Secrets Manager, Railway Variables).
