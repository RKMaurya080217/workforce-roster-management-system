# WRMS Firebase Cloud Messaging (FCM) Web Push Notification Setup Guide

## 1. Overview & Architecture

Workforce Roster Management System (WRMS) integrates **100% FREE** browser-based push notifications using **Firebase Cloud Messaging (FCM) Web Push** (HTTP v1 API).

### Key Architectural Principles

- **Zero-Cost Operation**: Utilizes Google Firebase's generous free tier for FCM Web Push. No paid SMS providers (Twilio, MSG91, Textlocal) or credit cards required.
- **Accurate Terminology**: Web Push notifications are browser-based push alerts, strictly distinguished from cellular SMS.
- **Email Primary**: Brevo HTTPS email remains the primary roster delivery mechanism. Push notifications serve as an instant alert layer.
- **Roster Notification Trigger & Copy**:
  - Push notifications are triggered **only after** roster email delivery succeeds (`result.isSuccess()`).
  - If email delivery fails, push notifications are never triggered.
  - Push failure never breaks email or roster operations (strict failure isolation).
  - Exact copy:
    - **Final Roster**: `Your final roster for {START_DATE} – {END_DATE} has been sent to your registered email.`
    - **Tentative Roster**: `Your tentative roster for {START_DATE} – {END_DATE} has been sent to your registered email.`
    - Dates are dynamically formatted in `Asia/Kolkata` timezone with en-dash separator (e.g., `14 Sep – 20 Sep 2026`).
- **Duplicate Suppression**: Notifications for the same cycle, employee, and roster state within a 15-minute window are suppressed to prevent spam.
- **Multi-Device Support**: Employees and administrators can register multiple browsers/devices. Inactive or unregistered tokens are automatically detected and deactivated.
- **Credential Separation**: Public client configuration (`apiKey`, `projectId`, `vapidKey`) is served via API to authorized clients, while private service account credentials remain strictly on the server.
- **Native Implementation**: Built with Java 17 `HttpClient`, `PKCS8EncodedKeySpec`, and `SHA256withRSA` signing for FCM HTTP v1 OAuth2 exchange—eliminating massive SDK dependencies and vulnerabilities.

---

## 2. Firebase Project Setup

### Step 2.1: Create a Firebase Project
1. Navigate to the [Firebase Console](https://console.firebase.google.com/).
2. Sign in with your Google Account.
3. Click **Add project** (or **Create a project**).
4. Enter a project name (e.g., `wrms-roster-management` or `wrms-prod`).
5. (Optional) Disable Google Analytics if not needed, then click **Create project**.

### Step 2.2: Register a Web Application
1. In your Firebase project overview, click the **Web icon** (`</>`) to add a web app.
2. Enter an App nickname (e.g., `WRMS Web`).
3. Leave "Firebase Hosting" unchecked unless you are hosting via Firebase.
4. Click **Register app**.
5. Firebase will display your SDK configuration object:
   ```javascript
   const firebaseConfig = {
     apiKey: "AIzaSy...",
     authDomain: "wrms-roster-management.firebaseapp.com",
     projectId: "wrms-roster-management",
     storageBucket: "wrms-roster-management.appspot.com",
     messagingSenderId: "123456789012",
     appId: "1:123456789012:web:abcdef12345678"
   };
   ```
   *Save these values for your environment variables.*

### Step 2.3: Generate Web Push Certificate (VAPID Key)
1. In the Firebase Console, click the **Gear icon (Project settings)** in the left sidebar.
2. Navigate to the **Cloud Messaging** tab.
3. Scroll down to the **Web configuration** section.
4. Under **Web Push certificates**, click **Generate key pair**.
5. Copy the generated **Key pair string** (e.g., `BNxxxx...`). This is your `FCM_WEB_VAPID_KEY`.

### Step 2.4: Generate Private Service Account Key
1. In **Project settings**, navigate to the **Service accounts** tab.
2. Ensure **Firebase Admin SDK** is selected.
3. Click **Generate new private key**, then click **Generate key**.
4. A JSON credentials file will download (e.g., `wrms-roster-management-firebase-adminsdk-xxxxx.json`).
5. Keep this file secure; it contains the private key used by WRMS server to sign FCM HTTP v1 push requests.

---

## 3. Configuration & Environment Variables

Configure WRMS using either environment variables (recommended for Railway / Docker / production) or `application.properties`.

### Environment Variable Reference

| Environment Variable | Property Name | Description | Example / Required |
|----------------------|---------------|-------------|--------------------|
| `FCM_ENABLED` | `fcm.enabled` | Enable or disable FCM push service | `true` (Default: `true`) |
| `FCM_WEB_API_KEY` | `fcm.web.api-key` | Firebase Web API Key | `AIzaSy...` (Required for client) |
| `FCM_WEB_AUTH_DOMAIN` | `fcm.web.auth-domain` | Firebase Auth Domain | `project.firebaseapp.com` |
| `FCM_WEB_PROJECT_ID` | `fcm.web.project-id` | Firebase Project ID | `wrms-roster-management` (Required) |
| `FCM_WEB_MESSAGING_SENDER_ID` | `fcm.web.messaging-sender-id` | Cloud Messaging Sender ID | `123456789012` (Required) |
| `FCM_WEB_APP_ID` | `fcm.web.app-id` | Firebase Web App ID | `1:123456789012:web:...` (Required) |
| `FCM_WEB_VAPID_KEY` | `fcm.web.vapid-key` | Web Push Public Key Pair (VAPID) | `BNxxx...` (Required for subscription) |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | `fcm.server.service-account-json` | Full JSON content or Base64 JSON of service account key | `{"type":"service_account",...}` |
| `FIREBASE_CREDENTIALS_PATH` | `fcm.server.credentials-path` | Path to service account JSON file on disk | `config/firebase-service-account.json` |

---

## 4. Deployment Setup

### Railway Deployment (Production)
1. Open your Railway project dashboard for the WRMS backend service.
2. Navigate to the **Variables** tab.
3. Add the public web configuration variables:
   - `FCM_ENABLED` = `true`
   - `FCM_WEB_API_KEY` = `<your-api-key>`
   - `FCM_WEB_PROJECT_ID` = `<your-project-id>`
   - `FCM_WEB_MESSAGING_SENDER_ID` = `<your-sender-id>`
   - `FCM_WEB_APP_ID` = `<your-app-id>`
   - `FCM_WEB_VAPID_KEY` = `<your-vapid-key>`
4. Add the server credentials variable:
   - Option A (Direct JSON string):
     - `FIREBASE_SERVICE_ACCOUNT_JSON` = paste the complete raw JSON content of your downloaded service account key file.
   - Option B (Base64-encoded string, recommended to avoid newline issues):
     - Run: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("path/to/key.json"))`
     - Set `FIREBASE_SERVICE_ACCOUNT_JSON` = `<base64-string>`
5. Deploy or restart the Railway service.

### Local Development Setup
1. Place your service account JSON file inside the project (e.g., `src/main/resources/firebase-key.json`).
   *(Ensure `*-key.json` or `firebase*.json` is in `.gitignore`).*
2. In `application-local.properties` or environment variables:
   ```properties
   fcm.enabled=true
   fcm.web.api-key=AIzaSy...
   fcm.web.project-id=wrms-roster-management
   fcm.web.messaging-sender-id=123456789012
   fcm.web.app-id=1:123456789012:web:...
   fcm.web.vapid-key=BNxxx...
   fcm.server.credentials-path=src/main/resources/firebase-key.json
   ```
3. If neither credentials file nor JSON is provided, WRMS starts cleanly in **Simulated Fallback Mode**:
   - Notifications are logged to the console with full payload details.
   - No crashes or startup failures occur.

---

## 5. Endpoints & Verification

### Available Endpoints

| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| `GET` | `/api/notifications/fcm/config` | Public / Authenticated | Returns public web configuration (`apiKey`, `projectId`, `vapidKey`, etc.) |
| `POST` | `/api/notifications/fcm/register-token` | Authenticated (Employee/Admin) | Registers or refreshes a browser device token |
| `POST` | `/api/notifications/fcm/unregister-token` | Authenticated (Employee/Admin) | Deactivates a browser device token |
| `GET` | `/api/notifications/fcm/status` | Authenticated (Employee/Admin) | Returns user's registration status and active device count |
| `POST` | `/api/notifications/fcm/test` | Admin Only (`ROLE_ADMIN`) | Sends test push notification: `WRMS test notification: Push notifications are working successfully.` |

### Verification Steps

1. **Test Public Configuration**:
   ```bash
   curl -s http://localhost:8080/api/notifications/fcm/config
   ```
   *Expected Response:*
   ```json
   {
     "configured": true,
     "enabled": true,
     "apiKey": "AIzaSy...",
     "projectId": "wrms-roster-management",
     "messagingSenderId": "123456789012",
     "appId": "1:123456789012:web:...",
     "vapidKey": "BNxxx..."
   }
   ```

2. **Verify Admin Test Push**:
   - Log in as Admin (`admin` / `admin123`).
   - Click the notification bell in the top navigation bar.
   - Click **Test Push** or call:
     ```bash
     curl -X POST http://localhost:8080/api/notifications/fcm/test \
       -H "Authorization: Bearer <ADMIN_JWT_TOKEN>"
     ```
   - *Expected Response:*
     ```json
     {
       "success": true,
       "message": "WRMS test notification: Push notifications are working successfully.",
       "deliveredCount": 1
     }
     ```

3. **Verify Automatic Roster Push Delivery**:
   - Generate or publish a tentative or final roster cycle for an employee who has enabled Web Push.
   - Verify Brevo email delivery succeeds.
   - Verify the browser displays an instant notification:
     > **Roster Published**  
     > `Your final roster for 14 Sep – 20 Sep 2026 has been sent to your registered email.`

---

## 6. Browser Requirements & Troubleshooting

- **HTTPS Requirement**: Modern browsers (Chrome, Edge, Firefox, Safari) enforce that Service Workers and Push API require **HTTPS** (or `localhost` for development). Production deployments on Railway automatically provide HTTPS.
- **Browser Permission Blocked**: If a user previously selected "Block" for notifications, the browser UI will display `Web Push: Blocked`. The user must open browser site settings (lock icon next to the URL) and reset the notification permission to "Allow".
- **Invalid Token Deactivation**: When an employee uninstalls a browser or clears site data, FCM returns `UNREGISTERED` or `INVALID_ARGUMENT`. WRMS automatically catches these errors and deactivates the invalid `DeviceToken` in the database, preventing stale dispatch attempts.
- **Service Worker Scope**: The service worker is hosted at `/firebase-messaging-sw.js` (root scope) so it can handle push events across all application paths.
