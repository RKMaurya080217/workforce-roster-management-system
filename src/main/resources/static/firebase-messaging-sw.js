/* ==========================================================================
   WRMS FIREBASE CLOUD MESSAGING (FCM) SERVICE WORKER
   Handles background push notifications for Workforce Roster Management System.
   ========================================================================== */

importScripts('https://www.gstatic.com/firebasejs/10.12.0/firebase-app-compat.js');
importScripts('https://www.gstatic.com/firebasejs/10.12.0/firebase-messaging-compat.js');

// Extract config from Service Worker URL search parameters if provided
const swUrl = new URL(self.location.href);
const apiKey = swUrl.searchParams.get('apiKey');
const projectId = swUrl.searchParams.get('projectId');
const messagingSenderId = swUrl.searchParams.get('messagingSenderId');
const appId = swUrl.searchParams.get('appId');

function initializeFirebase(config) {
  if (firebase.apps.length === 0 && config && config.apiKey && config.projectId) {
    firebase.initializeApp(config);
    setupMessaging();
  }
}

if (apiKey && projectId) {
  initializeFirebase({
    apiKey: apiKey,
    projectId: projectId,
    messagingSenderId: messagingSenderId,
    appId: appId
  });
}

// Support dynamic config injection via postMessage from client
self.addEventListener('message', (event) => {
  if (event.data && event.data.type === 'WRMS_FCM_CONFIG') {
    initializeFirebase(event.data.config);
  }
});

// Service Worker Immediate Activation
self.addEventListener('install', () => {
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(clients.claim());
});

function setupMessaging() {
  try {
    const messaging = firebase.messaging();

    messaging.onBackgroundMessage((payload) => {
      const title = (payload.notification && payload.notification.title)
          || (payload.data && payload.data.title)
          || 'WRMS Roster Notification';

      const body = (payload.notification && payload.notification.body)
          || (payload.data && payload.data.body)
          || 'You have a new update in WRMS.';

      const options = {
        body: body,
        icon: '/favicon.ico',
        badge: '/favicon.ico',
        vibrate: [200, 100, 200],
        data: payload.data || {},
        tag: (payload.data && payload.data.cycleId) ? `wrms-roster-${payload.data.cycleId}` : 'wrms-notification',
        renotify: true
      };

      return self.registration.showNotification(title, options);
    });
  } catch (err) {
    console.warn('[WRMS SW] Messaging setup error:', err);
  }
}

// Focus or open WRMS window upon clicking a notification
self.addEventListener('notificationclick', (event) => {
  event.notification.close();

  const targetUrl = (event.notification.data && (event.notification.data.clickUrl || event.notification.data.url)) || '/';

  event.waitUntil(
    clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clientList) => {
      for (const client of clientList) {
        if (client.url.includes(self.location.origin) && 'focus' in client) {
          return client.focus();
        }
      }
      if (clients.openWindow) {
        return clients.openWindow(targetUrl);
      }
    })
  );
});
