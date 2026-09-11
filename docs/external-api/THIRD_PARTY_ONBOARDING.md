# Third-Party Partner Integration & Onboarding Guide

Welcome to the **WRMS (Workforce Roster Management System)** integration program! Follow this step-by-step checklist to integrate your software with WRMS.

---

## Step 1: Request & Receive API Credentials

1. Contact the WRMS Administrator (Rajat Maurya) to request an API key for your application.
2. Specify the scopes needed:
   - `ROSTER_READ`: If you need shift assignments and duty schedules.
   - `EMPLOYEE_READ`: If you need employee names and codes.
   - `SHIFT_READ`: If you need shift definitions and hours.
   - `LEAVE_READ`: If you need leave approvals.
3. You will receive:
   - **Production Base URL**: `https://<YOUR-RAILWAY-APP>.up.railway.app`
   - **API Key**: `wrms_live_...`
   - **Authorized Scopes**

> [!CAUTION]
> Store your API key in a secure secret store or environment variable immediately. WRMS administrators cannot retrieve the key once issued because only a one-way SHA-256 hash is retained.

---

## Step 2: Configure Your Environment

Set up your configuration:
```env
WRMS_BASE_URL=https://<YOUR-RAILWAY-APP>.up.railway.app
WRMS_API_KEY=wrms_live_your_actual_key_here
```

---

## Step 3: Test with Postman

1. Download [`WRMS_External_API_v1.postman_collection.json`](WRMS_External_API_v1.postman_collection.json).
2. Open Postman -> **Import** -> Select the JSON file.
3. Set collection variables:
   - `baseUrl`: Your WRMS Base URL.
   - `apiKey`: Your API Key.
4. Run the **Ping & Diagnostics** request. Verify you receive `200 OK` with `status: "UP"` and your authorized scopes.

---

## Step 4: Call Your First Business API

Fetch the active weekly roster:
```http
GET /api/external/v1/rosters/current HTTP/1.1
Host: your-wrms-domain.up.railway.app
X-API-Key: wrms_live_...
Accept: application/json
```

Inspect the response:
- Verify `success: true`.
- Parse `data.assignments` for your integration.

---

## Step 5: Implement Error Handling & Retries

1. **401 Unauthorized**: Check if your key is correctly passed in `X-API-Key` or `Authorization: Bearer <key>`.
2. **403 Forbidden**: Verify your key has the required scope for the endpoint you are calling.
3. **429 Too Many Requests**: Implement exponential backoff or pause for the duration specified in the `Retry-After` header (usually 60 seconds).
4. **500 Internal Error**: Log the `requestId` from the response metadata and report it to WRMS support.

---

## What Should NEVER Be Shared with Third Parties

| Safe to Share | NEVER Share |
|---|---|
| Base URL | Admin login username or password |
| Versioned Endpoint URLs | Employee usernames or passwords |
| Dedicated API Key | MySQL database host or password |
| Granted Scopes | Railway dashboard credentials |
| Swagger UI URL | Brevo API key or Gmail SMTP password |
| Postman Collection | Internal JWT Secret |
