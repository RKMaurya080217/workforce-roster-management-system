# WRMS External API — Error Codes & Response Formats

All responses from the WRMS External API adhere to a consistent JSON envelope.

---

## 1. Standard Envelope Specification

### Success Response
```json
{
  "success": true,
  "data": { ... },
  "error": null,
  "meta": {
    "timestamp": "2026-09-11T19:00:00",
    "requestId": "6a91c944-ef29-43c3-b3bc-dcb65b0be043",
    "version": "v1"
  }
}
```

### Error Response
```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "ERROR_CODE",
    "message": "Human-readable description of what went wrong",
    "details": "Optional additional context or field-level validation errors"
  },
  "meta": {
    "timestamp": "2026-09-11T19:00:00",
    "requestId": "6a91c944-ef29-43c3-b3bc-dcb65b0be043",
    "version": "v1"
  }
}
```

---

## 2. HTTP Status Codes & Error Codes

| HTTP Status | Error Code | Description | Typical Cause |
|---|---|---|---|
| **400 Bad Request** | `BAD_REQUEST` | Malformed URL parameter or invalid date format | Date format is not `YYYY-MM-DD` |
| **400 Bad Request** | `VALIDATION_FAILED` | Request payload validation failed | Missing required field |
| **401 Unauthorized** | `UNAUTHORIZED` | Authentication missing or invalid | Missing or incorrect API key, deactivated key |
| **403 Forbidden** | `FORBIDDEN` | Insufficient permissions | API key lacks required scope (e.g. `ROSTER_READ`) |
| **404 Not Found** | `RESOURCE_NOT_FOUND` | Target entity does not exist | Invalid employee code or non-existent roster cycle |
| **429 Too Many Requests** | `RATE_LIMIT_EXCEEDED` | Request rate threshold exceeded | Exceeded 60 requests/minute |
| **500 Internal Error** | `INTERNAL_SERVER_ERROR` | Unexpected server condition | Server error; quote `requestId` to support |

---

## 3. Rate Limit Headers

When calling the API, rate limit status is communicated in response headers:
* `X-RateLimit-Limit`: Maximum requests permitted per window (e.g. `60`).
* `X-RateLimit-Remaining`: Remaining request quota in the current window.
* `Retry-After`: Number of seconds to wait before retrying (sent on 429).
