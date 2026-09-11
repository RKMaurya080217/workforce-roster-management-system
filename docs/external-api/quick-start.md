# WRMS External API — Quick Start Guide

Get up and running with the WRMS External API in under 5 minutes.

---

## 1. Prerequisites
You need:
1. **Base URL**: e.g., `http://localhost:8080` (Local) or `https://<YOUR-RAILWAY-APP>.up.railway.app` (Production).
2. **API Key**: Issued by WRMS Administrator (format: `wrms_live_<32-hex-characters>`).

---

## 2. Step 1: Connectivity & Key Check (`/ping`)

Send a `GET` request to `/api/external/v1/ping` with your API key:

### cURL
```bash
curl -X GET "http://localhost:8080/api/external/v1/ping" \
  -H "X-API-Key: wrms_live_dev_test_secret_key_change_in_prod" \
  -H "Accept: application/json"
```

### Response (200 OK)
```json
{
  "success": true,
  "data": {
    "status": "UP",
    "message": "WRMS External API v1 is reachable and credentials are valid",
    "client": "Default Integration Client",
    "authorizedScopes": [
      "ROSTER_READ",
      "EMPLOYEE_READ",
      "SHIFT_READ",
      "LEAVE_READ"
    ],
    "serverTime": "2026-09-11T19:00:00"
  },
  "error": null,
  "meta": {
    "timestamp": "2026-09-11T19:00:00",
    "requestId": "4c9e8361-b53e-473d-82d8-21d4bb796c6b",
    "version": "v1"
  }
}
```

---

## 3. Step 2: Fetch the Current Weekly Roster

```bash
curl -X GET "http://localhost:8080/api/external/v1/rosters/current" \
  -H "X-API-Key: wrms_live_dev_test_secret_key_change_in_prod"
```

### Response (200 OK)
```json
{
  "success": true,
  "data": {
    "id": 2539,
    "startDate": "2026-09-14",
    "endDate": "2026-09-20",
    "generatedAt": "2026-09-11T18:59:16",
    "assignmentCount": 49,
    "assignments": [
      {
        "id": 1201,
        "date": "2026-09-14",
        "dayOfWeek": "MONDAY",
        "shiftType": "MORNING",
        "employeeCode": "EMP001",
        "employeeName": "Rajat Maurya",
        "weeklyOff": false,
        "onLeave": false
      }
    ]
  },
  "error": null,
  "meta": {
    "timestamp": "2026-09-11T19:00:01",
    "requestId": "9d81640a-c215-46aa-b3e1-e6308cfcf683",
    "version": "v1"
  }
}
```

---

## 4. Step 3: Fetch Active Employees

```bash
curl -X GET "http://localhost:8080/api/external/v1/employees?activeOnly=true" \
  -H "X-API-Key: wrms_live_dev_test_secret_key_change_in_prod"
```

---

## 5. Step 4: Using Postman

1. Open Postman.
2. Click **Import** and choose `docs/external-api/WRMS_External_API_v1.postman_collection.json`.
3. In the collection settings, configure:
   - `baseUrl`: `http://localhost:8080`
   - `apiKey`: `wrms_live_dev_test_secret_key_change_in_prod`
4. Run the requests in the collection!
