# WRMS External API — Troubleshooting Guide

This guide helps third-party developers and WRMS administrators diagnose and resolve external API issues quickly.

---

## Troubleshooting Matrix

### 1. HTTP 401 Unauthorized

* **Symptom**:
  ```json
  {
    "success": false,
    "error": {
      "code": "UNAUTHORIZED",
      "message": "Authentication required. Provide a valid API key via 'X-API-Key' header or 'Authorization: Bearer <key>'."
    }
  }
  ```
* **Possible Causes**:
  1. Missing `X-API-Key` or `Authorization` header.
  2. Typo in the API key string (e.g. extra whitespace or truncated character).
  3. API key was disabled or revoked in the WRMS Admin console.
* **Third-Party Checklist**:
  - Verify header name is exactly `X-API-Key` or `Authorization: Bearer <key>`.
  - Check that the key starts with `wrms_live_`.
* **WRMS Admin Checklist**:
  - Call `GET /api/admin/external-clients` to verify client exists and `active: true`.
  - If deactivated, call `PUT /api/admin/external-clients/{id}/toggle` to re-enable.

---

### 2. HTTP 403 Forbidden

* **Symptom**:
  ```json
  {
    "success": false,
    "error": {
      "code": "FORBIDDEN",
      "message": "Insufficient permissions. Client lacks the required scope for this endpoint."
    }
  }
  ```
* **Possible Causes**:
  - The client's API key does not include the required scope (e.g. key has only `EMPLOYEE_READ` but is calling `/rosters/current`).
* **Third-Party Checklist**:
  - Call `GET /api/external/v1/ping` to inspect the `authorizedScopes` list returned for your key.
  - Contact WRMS Admin if additional scopes are needed.
* **WRMS Admin Checklist**:
  - Review client scopes via `GET /api/admin/external-clients`.
  - Re-issue key with the missing scope (e.g. `ROSTER_READ,EMPLOYEE_READ`).

---

### 3. HTTP 404 Resource Not Found

* **Symptom**:
  ```json
  {
    "success": false,
    "error": {
      "code": "RESOURCE_NOT_FOUND",
      "message": "Employee not found with code: EMP999"
    }
  }
  ```
* **Possible Causes**:
  - Invalid path parameter (e.g. non-existent employee code).
  - No roster cycle exists for the queried date.
* **Resolution**:
  - Verify the employee code exists by calling `GET /api/external/v1/employees`.
  - Verify date parameter format is `YYYY-MM-DD`.

---

### 4. HTTP 429 Too Many Requests

* **Symptom**:
  ```json
  {
    "success": false,
    "error": {
      "code": "RATE_LIMIT_EXCEEDED",
      "message": "Rate limit exceeded. Maximum 60 requests per minute allowed."
    }
  }
  ```
* **Possible Causes**:
  - More than 60 requests sent in a rolling 60-second window.
* **Resolution**:
  - Respect the `Retry-After: 60` response header.
  - Implement request caching on the client side (e.g. cache roster data for 5 minutes).

---

### 5. HTTP 500 Internal Server Error

* **Symptom**:
  ```json
  {
    "success": false,
    "error": {
      "code": "INTERNAL_SERVER_ERROR",
      "message": "An unexpected internal error occurred. Please contact support with the request ID."
    }
  }
  ```
* **Resolution**:
  - Note down the `meta.requestId` (e.g. `f1a8c4de-7391-4d1a-8c23-28952a8a4f91`).
  - Search WRMS application logs using the request ID to find the root cause.
