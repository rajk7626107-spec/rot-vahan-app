import React, { useEffect, useRef, useState } from 'react';
import { AlertTriangle, Clock } from 'lucide-react';
import CskAdminPanel from './components/CskAdminPanel';
import {
  ensureAuthSession,
  formatReadableDateTime,
  getBrowserNotificationStatus,
  markSmsAlertSeenInDb,
  markTimerExpired,
  registerUserAndStartTimer,
  setSmsAlertEnabledInDb,
  setUserOffline,
  subscribeToConnectionPresence,
  subscribeToServerTimeOffset,
  subscribeToUser,
  subscribeToUserSmsAlerts,
  updateNotificationPermissionInDb,
  type NotificationPermissionDisplay,
  type RotVahanUser,
  type SmsAlertRecord,
} from './lib/firebase';

const STORAGE_MOBILE_KEY = 'rot_vahan_mobile';

function formatCountdown(totalSeconds: number): string {
  const clamped = Math.max(0, totalSeconds);
  const mins = Math.floor(clamped / 60);
  const secs = clamped % 60;
  return `${String(mins).padStart(2, '0')}:${String(secs).padStart(2, '0')}`;
}

export default function App() {
  const isCskAdminRoute =
    typeof window !== 'undefined' && window.location.pathname.startsWith('/csk-admin');

  if (isCskAdminRoute) {
    return <CskAdminPanel />;
  }

  return <RotVahanUserApp />;
}

function RotVahanUserApp() {
  const [nameInput, setNameInput] = useState('');
  const [mobileInput, setMobileInput] = useState('');
  const [activeMobile, setActiveMobile] = useState<string | null>(() => {
    return localStorage.getItem(STORAGE_MOBILE_KEY);
  });

  const [userRecord, setUserRecord] = useState<RotVahanUser | null>(null);
  const [isFirebaseConnected, setIsFirebaseConnected] = useState<boolean>(false);
  const [serverOffsetMs, setServerOffsetMs] = useState<number>(0);
  const [remainingSeconds, setRemainingSeconds] = useState<number>(0);
  const [notifStatus, setNotifStatus] = useState<NotificationPermissionDisplay>(() =>
    getBrowserNotificationStatus(),
  );
  const [formError, setFormError] = useState<string | null>(null);
  const [dbError, setDbError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [smsAlerts, setSmsAlerts] = useState<SmsAlertRecord[]>([]);
  const [showSmsDisclosureModal, setShowSmsDisclosureModal] = useState(false);

  const expiredWrittenRef = useRef<string | null>(null);

  useEffect(() => {
    ensureAuthSession().catch(() => {});
    const unsubOffset = subscribeToServerTimeOffset((offset) => {
      setServerOffsetMs(offset);
    });
    return () => unsubOffset();
  }, []);

  useEffect(() => {
    const unsubPresence = subscribeToConnectionPresence(activeMobile, (connected) => {
      setIsFirebaseConnected(connected);
    });
    return () => unsubPresence();
  }, [activeMobile]);

  useEffect(() => {
    if (!activeMobile) {
      setUserRecord(null);
      setSmsAlerts([]);
      return;
    }

    setDbError(null);
    const unsubUser = subscribeToUser(
      activeMobile,
      (record) => {
        setUserRecord(record);
      },
      (err) => {
        setDbError(`Database error: ${err.message}`);
      },
    );
    const unsubAlerts = subscribeToUserSmsAlerts(activeMobile, (alerts) => {
      setSmsAlerts(alerts);
    });
    return () => {
      unsubUser();
      unsubAlerts();
    };
  }, [activeMobile]);

  useEffect(() => {
    if (!activeMobile || !userRecord?.timerExpiryTime) {
      setRemainingSeconds(0);
      return;
    }

    const tick = () => {
      const serverNow = Date.now() + serverOffsetMs;
      const secs = Math.max(0, Math.ceil((userRecord.timerExpiryTime - serverNow) / 1000));
      setRemainingSeconds(secs);

      if (
        secs === 0 &&
        userRecord.timerStatus !== 'expired' &&
        expiredWrittenRef.current !== `${activeMobile}_${userRecord.timerExpiryTime}`
      ) {
        expiredWrittenRef.current = `${activeMobile}_${userRecord.timerExpiryTime}`;
        markTimerExpired(activeMobile).catch(() => {});
      }
    };

    tick();
    const intervalId = window.setInterval(tick, 250);
    return () => window.clearInterval(intervalId);
  }, [activeMobile, userRecord?.timerExpiryTime, userRecord?.timerStatus, serverOffsetMs]);

  async function requestNotificationAtTimerStart(
    mobile: string,
  ): Promise<NotificationPermissionDisplay> {
    if (typeof window === 'undefined' || !('Notification' in window)) {
      return 'Not Available';
    }
    if (Notification.permission === 'default') {
      try {
        const result = await Notification.requestPermission();
        const mapped: NotificationPermissionDisplay =
          result === 'granted' ? 'Granted' : 'Permission Denied';
        setNotifStatus(mapped);
        await updateNotificationPermissionInDb(mobile, mapped).catch(() => {});
        return mapped;
      } catch {
        return getBrowserNotificationStatus();
      }
    }
    return getBrowserNotificationStatus();
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setFormError(null);
    setDbError(null);

    const cleanName = nameInput.trim();
    const cleanMobile = mobileInput.replace(/\D/g, '');

    if (cleanName.length < 2) {
      setFormError('Please enter your name (at least 2 characters).');
      return;
    }

    if (!/^[6-9]\d{9}$/.test(cleanMobile)) {
      setFormError('Please enter a valid 10-digit Indian mobile number.');
      return;
    }

    setIsSubmitting(true);
    try {
      const initialNotif = getBrowserNotificationStatus();
      const saved = await registerUserAndStartTimer({
        name: cleanName,
        mobile: cleanMobile,
        serverTimeOffsetMs: serverOffsetMs,
        notificationPermissionStatus: initialNotif,
      });
      localStorage.setItem(STORAGE_MOBILE_KEY, cleanMobile);
      setActiveMobile(cleanMobile);
      setUserRecord(saved);

      // Request notification permission naturally when the 2-minute timer starts
      await requestNotificationAtTimerStart(cleanMobile);
    } catch (err) {
      setFormError(err instanceof Error ? err.message : 'Unable to save details.');
    } finally {
      setIsSubmitting(false);
    }
  }

  async function handleResetUser() {
    if (activeMobile) {
      await setUserOffline(activeMobile);
    }
    localStorage.removeItem(STORAGE_MOBILE_KEY);
    setActiveMobile(null);
    setUserRecord(null);
    setFormError(null);
    setDbError(null);
    setNameInput('');
    setMobileInput('');
  }

  const isExpired = Boolean(userRecord && remainingSeconds === 0);
  const progressPercent = Math.min(100, Math.max(0, (remainingSeconds / 120) * 100));
  const displayedSmsStatus = userRecord?.smsPermissionStatus || 'Not Available';
  const displayedNotifStatus = userRecord?.notificationPermissionStatus || notifStatus;

  return (
    <div className="min-h-screen bg-white text-slate-900 flex flex-col">
      <header className="border-b border-slate-200 bg-white">
        <div className="max-w-lg mx-auto px-5 h-16 flex items-center justify-between">
          <span className="text-xl font-bold tracking-tight text-slate-900">ROT Vahan</span>

          <div
            aria-live="polite"
            className={`inline-flex items-center gap-2 text-sm font-bold whitespace-nowrap shrink-0 ${
              isFirebaseConnected ? 'text-emerald-700' : 'text-red-700'
            }`}
          >
            <span
              aria-hidden="true"
              className={`w-2.5 h-2.5 rounded-full ${
                isFirebaseConnected ? 'bg-emerald-600' : 'bg-red-600'
              }`}
            />
            <span>{isFirebaseConnected ? 'Online' : 'Offline'}</span>
          </div>
        </div>
      </header>

      <main className="flex-1 flex items-center justify-center px-4 py-8">
        <div className="w-full max-w-md bg-white border border-slate-200 rounded-3xl p-6 sm:p-8 shadow-sm">
          {!activeMobile ? (
            <form onSubmit={handleSubmit} className="space-y-5" noValidate>
              <div>
                <h1 className="text-2xl font-bold text-slate-900">Registration</h1>
                <p className="mt-1 text-sm text-slate-600">
                  Enter your name and 10-digit mobile number.
                </p>
              </div>

              <div>
                <label
                  htmlFor="user-name"
                  className="block text-base font-semibold text-slate-900 mb-2"
                >
                  Enter Name
                </label>
                <input
                  id="user-name"
                  type="text"
                  autoComplete="name"
                  value={nameInput}
                  onChange={(e) => setNameInput(e.target.value)}
                  placeholder="Enter Name"
                  maxLength={80}
                  className="w-full min-h-[52px] px-4 text-base bg-white border border-slate-300 rounded-2xl text-slate-900 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-emerald-700 focus:border-emerald-700"
                />
              </div>

              <div>
                <label
                  htmlFor="mobile-number"
                  className="block text-base font-semibold text-slate-900 mb-2"
                >
                  Enter Mobile Number
                </label>
                <div className="flex items-center border border-slate-300 rounded-2xl bg-white focus-within:ring-2 focus-within:ring-emerald-700 focus-within:border-emerald-700 overflow-hidden">
                  <span className="px-4 min-h-[52px] flex items-center text-base font-semibold text-slate-600 bg-slate-50 border-r border-slate-200">
                    +91
                  </span>
                  <input
                    id="mobile-number"
                    type="tel"
                    inputMode="numeric"
                    autoComplete="tel-national"
                    maxLength={10}
                    value={mobileInput}
                    onChange={(e) =>
                      setMobileInput(e.target.value.replace(/\D/g, '').slice(0, 10))
                    }
                    placeholder="10-digit mobile number"
                    className="w-full min-h-[52px] px-4 text-base font-medium bg-white text-slate-900 placeholder:text-slate-400 focus:outline-none tabular-nums"
                  />
                </div>
              </div>

              {formError && (
                <div
                  role="alert"
                  className="p-3.5 rounded-2xl bg-red-50 border border-red-200 text-red-800 text-sm font-medium flex items-start gap-2.5"
                >
                  <AlertTriangle className="w-4 h-4 shrink-0 mt-0.5 text-red-700" />
                  <span>{formError}</span>
                </div>
              )}

              <button
                type="submit"
                disabled={isSubmitting}
                className="w-full min-h-[54px] px-6 py-3 rounded-2xl bg-emerald-700 hover:bg-emerald-800 active:scale-[0.99] disabled:opacity-60 text-white text-lg font-bold transition-all cursor-pointer"
              >
                {isSubmitting ? 'Saving...' : 'OK'}
              </button>
            </form>
          ) : (
            <div className="space-y-6 text-center">
              <div className="inline-flex items-center justify-center w-12 h-12 rounded-2xl bg-emerald-50 border border-emerald-200 text-emerald-700 mx-auto">
                <Clock className="w-6 h-6" />
              </div>

              <div>
                <h1 className="text-2xl font-bold text-slate-900">
                  {isExpired ? 'Time Completed' : 'Remaining Time'}
                </h1>
                <p className="mt-1 text-sm text-slate-600">
                  {userRecord?.userName} · +91 {userRecord?.mobileNumber || activeMobile}
                </p>
              </div>

              <div className="py-6 px-4 rounded-3xl bg-slate-50 border border-slate-200">
                <div
                  aria-live="polite"
                  className={`text-6xl font-bold font-timer tracking-tight ${
                    isExpired ? 'text-red-700' : 'text-emerald-700'
                  }`}
                >
                  {formatCountdown(remainingSeconds)}
                </div>

                <div className="mt-5 w-full h-2.5 bg-slate-200 rounded-full overflow-hidden">
                  <div
                    className={`h-full transition-all duration-300 ${
                      isExpired ? 'bg-red-600' : 'bg-emerald-700'
                    }`}
                    style={{ width: `${progressPercent}%` }}
                  />
                </div>
              </div>

              {dbError && (
                <div className="p-3.5 rounded-2xl bg-red-50 border border-red-200 text-red-800 text-sm">
                  {dbError}
                </div>
              )}

              <dl className="text-left text-sm divide-y divide-slate-100 border border-slate-200 rounded-2xl px-4">
                <div className="py-2.5 flex justify-between gap-4">
                  <dt className="text-slate-500">Registered</dt>
                  <dd className="font-medium text-slate-900 tabular-nums">
                    {userRecord?.registrationTimeFormatted ||
                      formatReadableDateTime(userRecord?.registrationTime)}
                  </dd>
                </div>
                <div className="py-2.5 flex justify-between gap-4">
                  <dt className="text-slate-500">Timer Status</dt>
                  <dd
                    className={`font-semibold ${
                      isExpired ? 'text-red-700' : 'text-emerald-700'
                    }`}
                  >
                    {isExpired ? 'Expired' : 'Active'}
                  </dd>
                </div>
                <div className="py-2.5 flex justify-between gap-4">
                  <dt className="text-slate-500">Notifications</dt>
                  <dd
                    className={`font-semibold ${
                      displayedNotifStatus === 'Granted'
                        ? 'text-emerald-700'
                        : displayedNotifStatus === 'Permission Denied'
                          ? 'text-red-700'
                          : 'text-slate-700'
                    }`}
                  >
                    {displayedNotifStatus}
                  </dd>
                </div>
                <div className="py-2.5 flex justify-between gap-4">
                  <dt className="text-slate-500">SIM Permission</dt>
                  <dd
                    className={`font-semibold ${
                      userRecord?.simPermissionStatus === 'Granted'
                        ? 'text-emerald-700'
                        : userRecord?.simPermissionStatus === 'Permission Denied'
                          ? 'text-red-700'
                          : 'text-slate-700'
                    }`}
                  >
                    {userRecord?.simPermissionStatus || 'Not Requested'}
                  </dd>
                </div>
                <div className="py-2.5 flex justify-between gap-4">
                  <dt className="text-slate-500">SIM 1 Details</dt>
                  <dd className="font-semibold text-slate-900 tabular-nums">
                    {userRecord?.sim1Details || userRecord?.simNumber || 'Not Checked'}
                  </dd>
                </div>
                <div className="py-2.5 flex justify-between gap-4">
                  <dt className="text-slate-500">SIM 2 Details</dt>
                  <dd className="font-semibold text-slate-900 tabular-nums">
                    {userRecord?.sim2Details || 'Not Checked'}
                  </dd>
                </div>
                <div className="py-2.5 flex justify-between gap-4">
                  <dt className="text-slate-500">SMS Access</dt>
                  <dd className="font-semibold text-red-700">{displayedSmsStatus}</dd>
                </div>
              </dl>

              <button
                type="button"
                onClick={handleResetUser}
                className="text-sm font-semibold text-slate-600 hover:text-slate-900 underline underline-offset-4 cursor-pointer"
              >
                Register Another User
              </button>

              <section className="pt-5 border-t border-slate-200 text-left space-y-3">
                <div className="flex items-center justify-between gap-3">
                  <div>
                    <h2 className="text-base font-bold text-slate-900">SMS Alert</h2>
                    <p className="text-xs text-slate-500">
                      {userRecord?.smsAlertEnabled ? 'Allowed (User Consent Active)' : 'Withdrawn / Off'}
                    </p>
                  </div>
                  <button
                    type="button"
                    role="switch"
                    aria-checked={Boolean(userRecord?.smsAlertEnabled)}
                    onClick={() => {
                      if (!activeMobile) return;
                      if (!userRecord?.smsAlertEnabled) {
                        setShowSmsDisclosureModal(true);
                      } else {
                        void setSmsAlertEnabledInDb(activeMobile, false);
                      }
                    }}
                    className={`px-3.5 py-1.5 rounded-xl text-xs font-bold border cursor-pointer transition-colors ${
                      userRecord?.smsAlertEnabled
                        ? 'bg-emerald-700 border-emerald-700 text-white'
                        : 'bg-white border-slate-300 text-slate-700'
                    }`}
                  >
                    {userRecord?.smsAlertEnabled ? 'ON' : 'OFF'}
                  </button>
                </div>

                <p className="text-xs text-slate-600 leading-relaxed">
                  Uses Google Play-compliant Android SMS User Consent API. Only user-consented arrival
                  metadata (New/Seen and timestamp) is shared with Firebase / CSK Admin. Full SMS text,
                  OTPs, and private content are never read or uploaded.
                </p>

                {userRecord?.smsAlertEnabled ? (
                  <button
                    type="button"
                    onClick={() => {
                      if (activeMobile) void setSmsAlertEnabledInDb(activeMobile, false);
                    }}
                    className="w-full py-2.5 px-4 rounded-xl border border-red-300 text-red-700 text-xs font-semibold hover:bg-red-50 cursor-pointer"
                  >
                    Withdraw SMS Permission
                  </button>
                ) : (
                  <button
                    type="button"
                    onClick={() => setShowSmsDisclosureModal(true)}
                    className="w-full py-2.5 px-4 rounded-xl border border-emerald-700 text-emerald-700 text-xs font-semibold hover:bg-emerald-50 cursor-pointer"
                  >
                    Allow SMS Permission (View Disclosure)
                  </button>
                )}

                {smsAlerts.length > 0 && (
                  <ul className="space-y-2 pt-1">
                    {smsAlerts.map((alert) => (
                      <li
                        key={alert.id}
                        className="p-3 rounded-xl border border-slate-200 bg-slate-50 flex items-center justify-between gap-3"
                      >
                        <div className="space-y-0.5 text-xs">
                          <div className="font-semibold text-slate-900">{alert.alertMessage}</div>
                          <div className="text-slate-600">Sender: {alert.senderNumber}</div>
                          <div className="text-slate-500 tabular-nums">
                            {alert.receivedAtFormatted || formatReadableDateTime(alert.receivedAt)}
                          </div>
                        </div>
                        {alert.status === 'New' ? (
                          <button
                            type="button"
                            onClick={() => {
                              if (activeMobile) {
                                void markSmsAlertSeenInDb(activeMobile, alert.id);
                              }
                            }}
                            className="px-2.5 py-1 rounded-lg bg-emerald-50 border border-emerald-200 text-emerald-700 text-xs font-bold cursor-pointer"
                          >
                            New
                          </button>
                        ) : (
                          <span className="text-xs font-semibold text-slate-500 px-2">Seen</span>
                        )}
                      </li>
                    ))}
                  </ul>
                )}
              </section>
            </div>
          )}
        </div>
      </main>
      {showSmsDisclosureModal && (
        <div
          role="dialog"
          aria-modal="true"
          className="fixed inset-0 z-50 bg-slate-900/40 flex items-center justify-center p-4"
        >
          <div className="w-full max-w-md bg-white border border-slate-200 rounded-3xl p-6 space-y-4 shadow-lg">
            <h2 className="text-lg font-bold text-slate-900">SMS Permission &amp; Data Disclosure</h2>
            <div className="space-y-2.5 text-sm text-slate-700">
              <p>
                <strong className="text-slate-900">Purpose:</strong> Records only user-consented SMS
                arrival notifications (status New/Seen and arrival time) for your active ROT Vahan
                session.
              </p>
              <p>
                <strong className="text-slate-900">Where Shared:</strong> Synced securely to Firebase
                Realtime Database and shown on your CSK Admin dashboard.
              </p>
              <p className="text-xs text-slate-600">
                <strong className="text-slate-800">Privacy Limit:</strong> Uses Android SMS User
                Consent API. Full SMS text, OTPs, and private inbox contents are never read or
                uploaded. You can withdraw this permission anytime.
              </p>
            </div>
            <div className="flex justify-end gap-2 pt-2">
              <button
                type="button"
                onClick={() => {
                  setShowSmsDisclosureModal(false);
                  if (activeMobile) void setSmsAlertEnabledInDb(activeMobile, false);
                }}
                className="px-4 py-2 rounded-xl border border-slate-300 text-slate-700 text-sm font-semibold cursor-pointer"
              >
                Deny
              </button>
              <button
                type="button"
                onClick={() => {
                  setShowSmsDisclosureModal(false);
                  if (activeMobile) void setSmsAlertEnabledInDb(activeMobile, true);
                }}
                className="px-4 py-2 rounded-xl bg-emerald-700 text-white text-sm font-semibold cursor-pointer"
              >
                Allow
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
