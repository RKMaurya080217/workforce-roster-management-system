/* ==========================================================================
   WRMS FIREBASE CLOUD MESSAGING (FCM) WEB PUSH CLIENT MODULE
   Provides clean, non-intrusive Web Push registration and notifications.
   ========================================================================== */

(function (window) {
  'use strict';

  const WrmsFcm = {
    config: null,
    messaging: null,
    swRegistration: null,
    currentToken: null,
    initialized: false,

    isSupported() {
      return (
        'serviceWorker' in navigator &&
        'PushManager' in window &&
        'Notification' in window &&
        typeof window.firebase !== 'undefined'
      );
    },

    getPermissionState() {
      if (!('Notification' in window)) return 'unsupported';
      return Notification.permission; // 'granted', 'denied', or 'default'
    },

    detectDeviceType() {
      const ua = navigator.userAgent || '';
      let os = 'Unknown OS';
      if (ua.includes('Win')) os = 'Windows';
      else if (ua.includes('Mac')) os = 'macOS';
      else if (ua.includes('Linux')) os = 'Linux';
      else if (ua.includes('Android')) os = 'Android';
      else if (ua.includes('iPhone') || ua.includes('iPad')) os = 'iOS';

      let browser = 'Browser';
      if (ua.includes('Chrome') && !ua.includes('Edg')) browser = 'Chrome';
      else if (ua.includes('Edg')) browser = 'Edge';
      else if (ua.includes('Firefox')) browser = 'Firefox';
      else if (ua.includes('Safari') && !ua.includes('Chrome')) browser = 'Safari';

      return `${browser} on ${os}`;
    },

    async fetchConfig() {
      if (this.config) return this.config;
      try {
        const token = sessionStorage.getItem('wrmsToken');
        const headers = { Accept: 'application/json' };
        if (token) headers['Authorization'] = `Bearer ${token}`;

        const res = await fetch('/api/notifications/fcm/config', { headers });
        if (!res.ok) return null;
        this.config = await res.json();
        return this.config;
      } catch (e) {
        console.warn('[WRMS FCM] Failed to fetch FCM config from backend:', e.message);
        return null;
      }
    },

    async init() {
      if (this.initialized) return;
      if (!this.isSupported()) {
        console.info('[WRMS FCM] Web push is not supported in this browser environment.');
        return;
      }

      const cfg = await this.fetchConfig();
      if (!cfg || !cfg.enabled || !cfg.configured) {
        console.info('[WRMS FCM] FCM is not configured or enabled on the server.');
        return;
      }

      try {
        if (window.firebase.apps.length === 0) {
          window.firebase.initializeApp({
            apiKey: cfg.apiKey,
            projectId: cfg.projectId,
            messagingSenderId: cfg.messagingSenderId,
            appId: cfg.appId
          });
        }

        this.messaging = window.firebase.messaging();

        // Register the background service worker with search params for initialization
        const swQuery = `?apiKey=${encodeURIComponent(cfg.apiKey)}&projectId=${encodeURIComponent(cfg.projectId)}&messagingSenderId=${encodeURIComponent(cfg.messagingSenderId)}&appId=${encodeURIComponent(cfg.appId)}`;
        this.swRegistration = await navigator.serviceWorker.register('/firebase-messaging-sw.js' + swQuery);

        // Notify service worker of config in case it is already running
        if (this.swRegistration.active) {
          this.swRegistration.active.postMessage({
            type: 'WRMS_FCM_CONFIG',
            config: {
              apiKey: cfg.apiKey,
              projectId: cfg.projectId,
              messagingSenderId: cfg.messagingSenderId,
              appId: cfg.appId
            }
          });
        }

        // Handle foreground notifications seamlessly
        this.messaging.onMessage((payload) => {
          console.info('[WRMS FCM] Foreground message received:', payload);
          const title = payload.notification?.title || payload.data?.title || 'WRMS Notification';
          const body = payload.notification?.body || payload.data?.body || '';

          // Show in-app toast if toast function exists
          if (typeof window.toast === 'function') {
            window.toast(`🔔 ${title}: ${body}`, 'info');
          }

          // Trigger in-app notification refresh
          if (typeof window.fetchNotifications === 'function') {
            window.fetchNotifications().catch(() => {});
          }
        });

        this.initialized = true;
        console.info('[WRMS FCM] Firebase Cloud Messaging client initialized successfully.');

        // If permission was already granted in a previous session, silently register/refresh token
        if (Notification.permission === 'granted') {
          await this.retrieveAndRegisterToken(false);
        }
      } catch (err) {
        console.warn('[WRMS FCM] Error during FCM client initialization:', err);
      }
    },

    async requestPermissionAndRegister() {
      if (!this.isSupported()) {
        throw new Error('Web Push Notifications are not supported in your browser.');
      }

      const cfg = await this.fetchConfig();
      if (!cfg || !cfg.enabled || !cfg.configured) {
        throw new Error('Push notification service is not configured on the server. Please check with your administrator.');
      }

      if (!this.initialized) {
        await this.init();
      }

      const perm = await Notification.requestPermission();
      if (perm !== 'granted') {
        throw new Error('Notification permission was ' + perm + '. Please allow notifications in your browser settings.');
      }

      return await this.retrieveAndRegisterToken(true);
    },

    async retrieveAndRegisterToken(isUserInitiated = false) {
      if (!this.messaging || !this.config || !this.config.vapidKey) {
        throw new Error('FCM messaging or VAPID key is unavailable.');
      }

      try {
        const token = await this.messaging.getToken({
          vapidKey: this.config.vapidKey,
          serviceWorkerRegistration: this.swRegistration
        });

        if (!token) {
          throw new Error('No registration token returned from Firebase.');
        }

        this.currentToken = token;

        // Register token with WRMS backend
        const authToken = sessionStorage.getItem('wrmsToken');
        if (!authToken) {
          console.warn('[WRMS FCM] User not logged in; skipping backend token registration.');
          return token;
        }

        const res = await fetch('/api/notifications/fcm/register-token', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Accept: 'application/json',
            Authorization: `Bearer ${authToken}`
          },
          body: JSON.stringify({
            token: token,
            deviceType: this.detectDeviceType()
          })
        });

        const data = await res.json().catch(() => ({}));
        if (!res.ok || !data.success) {
          throw new Error(data.message || 'Failed to register token with server.');
        }

        localStorage.setItem('wrms_fcm_token_registered', 'true');
        console.info('[WRMS FCM] Device successfully registered for push notifications.');
        return token;
      } catch (err) {
        console.error('[WRMS FCM] Failed to retrieve or register FCM token:', err);
        throw err;
      }
    },

    async unregister() {
      if (this.messaging && this.currentToken) {
        try {
          await this.messaging.deleteToken();
        } catch (e) {
          console.warn('[WRMS FCM] deleteToken warning:', e);
        }
      }

      const authToken = sessionStorage.getItem('wrmsToken');
      if (authToken && this.currentToken) {
        try {
          await fetch('/api/notifications/fcm/unregister-token', {
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
              Authorization: `Bearer ${authToken}`
            },
            body: JSON.stringify({ token: this.currentToken })
          });
        } catch (e) {
          console.warn('[WRMS FCM] unregister endpoint warning:', e);
        }
      }

      this.currentToken = null;
      localStorage.removeItem('wrms_fcm_token_registered');
    },

    async getStatus() {
      const supported = this.isSupported();
      const permission = this.getPermissionState();
      let registered = false;
      let activeDeviceCount = 0;
      let pushConfigured = false;

      const authToken = sessionStorage.getItem('wrmsToken');
      if (authToken) {
        try {
          const res = await fetch('/api/notifications/fcm/status', {
            headers: { Authorization: `Bearer ${authToken}` }
          });
          if (res.ok) {
            const data = await res.json();
            registered = data.registered || false;
            activeDeviceCount = data.activeDeviceCount || 0;
            pushConfigured = data.pushConfigured || false;
          }
        } catch (ignored) {}
      }

      return {
        supported,
        permission,
        registered,
        activeDeviceCount,
        pushConfigured
      };
    },

    async getDetailedDiagnostics() {
      const isHttps = window.location.protocol === 'https:' || window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1';
      const supported = this.isSupported();
      const permission = this.getPermissionState();
      const swState = this.swRegistration ? 'REGISTERED' : (navigator.serviceWorker ? 'UNREGISTERED' : 'UNSUPPORTED');
      const fcmInit = this.initialized && !!this.messaging;
      const tokenPresent = !!this.currentToken;
      const backendRegistered = localStorage.getItem('wrms_fcm_token_registered') === 'true';

      let serverDiag = {};
      const authToken = sessionStorage.getItem('wrmsToken');
      if (authToken) {
        try {
          const res = await fetch('/api/notifications/fcm/diagnostics', {
            headers: { Authorization: `Bearer ${authToken}` }
          });
          if (res.ok) serverDiag = await res.json();
        } catch (_) {}
      }

      return {
        https: isHttps,
        browserSupported: supported,
        permission: permission.toUpperCase(),
        serviceWorker: swState,
        fcmInitialized: fcmInit,
        tokenPresent: tokenPresent,
        backendRegistered: backendRegistered,
        deviceType: this.detectDeviceType(),
        serverConfigured: serverDiag.serverConfigured || false,
        webConfigured: serverDiag.webConfigured || (this.config && this.config.configured) || false,
        activeDeviceCount: serverDiag.userActiveDevices || 0,
        mode: serverDiag.mode || 'UNKNOWN'
      };
    }
  };

  window.WrmsFcm = WrmsFcm;
})(window);
