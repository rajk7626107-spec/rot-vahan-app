import React, { useEffect, useMemo, useState } from 'react';
import type { User } from 'firebase/auth';
import { KeyRound, Lock, LogOut, Search, Shield, ShieldAlert, Smartphone } from 'lucide-react';
import {
  changeAdminPassword,
  checkIsAdmin,
  formatReadableDateTime,
  signInAdminWithEmail,
  signInAdminWithGoogle,
  signOutAdmin,
  subscribeToAllUsers,
  subscribeToAuth,
  subscribeToConnectionPresence,
  subscribeToServerTimeOffset,
  type RotVahanUser,
} from '../lib/firebase';

function formatCountdown(totalSeconds: number): string {
  const clamped = Math.max(0, totalSeconds);
  const mins = Math.floor(clamped / 60);
  const secs = clamped % 60;
  return `${String(mins).padStart(2, '0')}:${String(secs).padStart(2, '0')}`;
}

export default function CskAdminPanel() {
  const [authUser, setAuthUser] = useState<User | null>(null);
  const [authReady, setAuthReady] = useState(false);
  const [isAdminVerified, setIsAdminVerified] = useState(false);

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [authError, setAuthError] = useState<string | null>(null);
  const [isSigningIn, setIsSigningIn] = useState(false);

  const [users, setUsers] = useState<RotVahanUser[]>([]);
  const [loading, setLoading] = useState(false);
  const [dbError, setDbError] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [firebaseConnected, setFirebaseConnected] = useState(false);
  const [serverOffsetMs, setServerOffsetMs] = useState(0);
  const [nowMs, setNowMs] = useState(() => Date.now());
  const [lastSyncedTime, setLastSyncedTime] = useState<string>('—');

  const [showPasswordModal, setShowPasswordModal] = useState(false);
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [passwordMessage, setPasswordMessage] = useState<{ type: 'ok' | 'err'; text: string } | null>(
    null,
  );
  const [isUpdatingPassword, setIsUpdatingPassword] = useState(false);

  useEffect(() => {
    const id = window.setInterval(() => setNowMs(Date.now()), 250);
    return () => window.clearInterval(id);
  }, []);

  useEffect(() => {
    const unsubOffset = subscribeToServerTimeOffset((offset) => setServerOffsetMs(offset));
    const unsubConn = subscribeToConnectionPresence(null, (connected) =>
      setFirebaseConnected(connected),
    );
    return () => {
      unsubOffset();
      unsubConn();
    };
  }, []);

  useEffect(() => {
    const unsub = subscribeToAuth(async (user) => {
      setAuthUser(user);
      if (user && !user.isAnonymous) {
        const verified = await checkIsAdmin(user.uid);
        setIsAdminVerified(verified);
      } else {
        setIsAdminVerified(false);
      }
      setAuthReady(true);
    });
    return () => unsub();
  }, []);

  useEffect(() => {
    if (!authUser || authUser.isAnonymous || !isAdminVerified) {
      setUsers([]);
      return;
    }

    setLoading(true);
    setDbError(null);
    const unsub = subscribeToAllUsers(
      (list) => {
        setUsers(list);
        setLoading(false);
        setLastSyncedTime(
          new Date().toLocaleTimeString('en-IN', {
            hour: '2-digit',
            minute: '2-digit',
            second: '2-digit',
            hour12: true,
          }),
        );
      },
      (err) => {
        setDbError(err.message);
        setLoading(false);
      },
    );
    return () => unsub();
  }, [authUser, isAdminVerified]);

  async function handleEmailLogin(e: React.FormEvent) {
    e.preventDefault();
    setAuthError(null);
    setIsSigningIn(true);
    try {
      const user = await signInAdminWithEmail(email, password);
      const verified = await checkIsAdmin(user.uid);
      setIsAdminVerified(verified);
    } catch (err) {
      setAuthError(err instanceof Error ? err.message : 'Sign-in failed.');
    } finally {
      setIsSigningIn(false);
    }
  }

  async function handleGoogleLogin() {
    setAuthError(null);
    setIsSigningIn(true);
    try {
      const user = await signInAdminWithGoogle();
      const verified = await checkIsAdmin(user.uid);
      setIsAdminVerified(verified);
    } catch (err) {
      setAuthError(err instanceof Error ? err.message : 'Google sign-in failed.');
    } finally {
      setIsSigningIn(false);
    }
  }

  async function handleChangePassword(e: React.FormEvent) {
    e.preventDefault();
    setPasswordMessage(null);
    if (newPassword.length < 6) {
      setPasswordMessage({ type: 'err', text: 'New password must be at least 6 characters.' });
      return;
    }
    setIsUpdatingPassword(true);
    try {
      await changeAdminPassword(currentPassword, newPassword);
      setPasswordMessage({ type: 'ok', text: 'Admin password updated successfully.' });
      setCurrentPassword('');
      setNewPassword('');
    } catch (err) {
      setPasswordMessage({
        type: 'err',
        text: err instanceof Error ? err.message : 'Unable to update password.',
      });
    } finally {
      setIsUpdatingPassword(false);
    }
  }

  const filteredUsers = useMemo(() => {
    const q = searchQuery.trim().toLowerCase();
    if (!q) return users;
    return users.filter(
      (u) =>
        u.userName.toLowerCase().includes(q) ||
        u.mobileNumber.toLowerCase().includes(q),
    );
  }, [users, searchQuery]);

  const onlineCount = useMemo(
    () => users.filter((u) => u.connectionStatus === 'online' || u.isOnline === true).length,
    [users],
  );
  const offlineCount = users.length - onlineCount;

  function getTimerDisplay(user: RotVahanUser): string {
    if (!user.timerExpiryTime) return '—';
    const serverNow = nowMs + serverOffsetMs;
    const secs = Math.max(0, Math.ceil((user.timerExpiryTime - serverNow) / 1000));
    return secs > 0 ? `Active · ${formatCountdown(secs)}` : 'Expired (00:00)';
  }

  return (
    <div className="min-h-screen bg-white text-slate-900 flex flex-col">
      {/* Preserves CSK ADMIN header style */}
      <header className="border-b border-slate-200 bg-white sticky top-0 z-30">
        <div className="max-w-4xl mx-auto px-4 sm:px-6 h-16 flex items-center justify-between gap-4">
          <div className="flex items-center gap-2.5">
            <Shield className="w-5 h-5 text-blue-600" />
            <span className="text-lg font-bold tracking-tight text-slate-900">CSK ADMIN</span>
          </div>

          <div className="flex items-center gap-4 text-sm">
            <span className={firebaseConnected ? 'text-emerald-700 font-semibold' : 'text-red-700 font-semibold'}>
              {firebaseConnected ? 'Firebase Connected' : 'Disconnected'}
            </span>
            {authUser && !authUser.isAnonymous && (
              <button
                type="button"
                onClick={() => signOutAdmin()}
                className="px-3 py-1.5 rounded-xl border border-slate-300 text-slate-800 font-semibold text-xs hover:bg-slate-50 inline-flex items-center gap-1.5 cursor-pointer"
              >
                <LogOut className="w-3.5 h-3.5" />
                <span>Sign Out</span>
              </button>
            )}
          </div>
        </div>
      </header>

      <main className="flex-1 w-full max-w-4xl mx-auto px-4 sm:px-6 py-6 space-y-6">
        {!authReady ? (
          <div className="py-16 text-center text-slate-500">Checking admin session...</div>
        ) : !authUser || authUser.isAnonymous ? (
          <div className="max-w-md mx-auto bg-white border border-slate-200 rounded-3xl p-6 sm:p-8 shadow-sm">
            <div className="w-12 h-12 rounded-2xl bg-blue-50 border border-blue-200 text-blue-600 flex items-center justify-center mb-4">
              <Lock className="w-6 h-6" />
            </div>
            <h1 className="text-2xl font-bold text-slate-900">CSK ADMIN Sign In</h1>
            <p className="mt-1.5 text-sm text-slate-600">
              Sign in with your authorized Firebase Admin account on sk-admin-panel-886ef.
            </p>

            <form onSubmit={handleEmailLogin} className="mt-5 space-y-4">
              <input
                type="email"
                required
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="Admin Email"
                className="w-full min-h-[48px] px-4 rounded-xl border border-slate-300 bg-white text-slate-900 text-base focus:outline-none focus:ring-2 focus:ring-blue-600"
              />
              <input
                type="password"
                required
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="Password"
                className="w-full min-h-[48px] px-4 rounded-xl border border-slate-300 bg-white text-slate-900 text-base focus:outline-none focus:ring-2 focus:ring-blue-600"
              />
              {authError && (
                <div className="p-3 rounded-xl bg-red-50 border border-red-200 text-red-800 text-sm">
                  {authError}
                </div>
              )}
              <button
                type="submit"
                disabled={isSigningIn}
                className="w-full min-h-[50px] px-5 py-3 rounded-xl bg-blue-600 hover:bg-blue-700 text-white font-bold text-base cursor-pointer disabled:opacity-60"
              >
                {isSigningIn ? 'Signing in...' : 'Sign In'}
              </button>
            </form>

            <button
              type="button"
              onClick={handleGoogleLogin}
              disabled={isSigningIn}
              className="mt-3 w-full min-h-[48px] px-5 py-2.5 rounded-xl border border-slate-300 bg-white hover:bg-slate-50 text-slate-900 font-semibold text-sm cursor-pointer"
            >
              Continue with Google
            </button>
          </div>
        ) : !isAdminVerified ? (
          <div className="max-w-lg mx-auto bg-white border border-slate-200 rounded-3xl p-6 sm:p-8 shadow-sm space-y-4">
            <div className="w-12 h-12 rounded-2xl bg-amber-50 border border-amber-200 text-amber-700 flex items-center justify-center">
              <ShieldAlert className="w-6 h-6" />
            </div>
            <h1 className="text-xl font-bold text-slate-900">Admin Access Required</h1>
            <p className="text-sm text-slate-700">
              Add{' '}
              <code className="font-timer bg-slate-100 px-1.5 py-0.5 rounded">
                admins/{authUser.uid}: true
              </code>{' '}
              in Firebase Realtime Database.
            </p>
            <button
              type="button"
              onClick={async () => setIsAdminVerified(await checkIsAdmin(authUser.uid))}
              className="min-h-[44px] px-4 py-2 rounded-xl bg-blue-600 text-white font-semibold text-sm cursor-pointer"
            >
              Re-check Access
            </button>
          </div>
        ) : (
          <>
            {/* Device Overview Header matching screenshot */}
            <div className="space-y-4">
              <div>
                <p className="text-xs text-slate-500 font-timer">Synced {lastSyncedTime}</p>
                <h1 className="text-3xl font-bold text-slate-900 mt-1">Device Overview</h1>
              </div>

              <button
                type="button"
                onClick={() => {
                  setPasswordMessage(null);
                  setShowPasswordModal(true);
                }}
                className="w-full sm:w-auto min-h-[48px] px-6 py-3 rounded-2xl bg-blue-600 hover:bg-blue-700 text-white font-semibold text-sm inline-flex items-center justify-center gap-2 cursor-pointer shadow-sm"
              >
                <KeyRound className="w-4 h-4" />
                <span>Change Password</span>
              </button>
            </div>

            {showPasswordModal && (
              <div
                role="dialog"
                aria-modal="true"
                className="fixed inset-0 z-50 bg-slate-900/40 flex items-center justify-center p-4"
              >
                <form
                  onSubmit={handleChangePassword}
                  className="w-full max-w-md bg-white border border-slate-200 rounded-3xl p-6 space-y-4 shadow-lg"
                >
                  <h2 className="text-xl font-bold text-slate-900">Change Admin Password</h2>
                  <input
                    type="password"
                    required
                    value={currentPassword}
                    onChange={(e) => setCurrentPassword(e.target.value)}
                    placeholder="Current Password"
                    className="w-full min-h-[48px] px-4 rounded-xl border border-slate-300 text-slate-900 text-base focus:outline-none focus:ring-2 focus:ring-blue-600"
                  />
                  <input
                    type="password"
                    required
                    minLength={6}
                    value={newPassword}
                    onChange={(e) => setNewPassword(e.target.value)}
                    placeholder="New Password (min 6 chars)"
                    className="w-full min-h-[48px] px-4 rounded-xl border border-slate-300 text-slate-900 text-base focus:outline-none focus:ring-2 focus:ring-blue-600"
                  />
                  {passwordMessage && (
                    <div
                      className={`p-3 rounded-xl text-sm border ${
                        passwordMessage.type === 'ok'
                          ? 'bg-emerald-50 border-emerald-200 text-emerald-800'
                          : 'bg-red-50 border-red-200 text-red-800'
                      }`}
                    >
                      {passwordMessage.text}
                    </div>
                  )}
                  <div className="flex justify-end gap-2 pt-2">
                    <button
                      type="button"
                      onClick={() => setShowPasswordModal(false)}
                      className="px-4 py-2.5 rounded-xl border border-slate-300 text-slate-700 font-semibold text-sm cursor-pointer"
                    >
                      Close
                    </button>
                    <button
                      type="submit"
                      disabled={isUpdatingPassword}
                      className="px-5 py-2.5 rounded-xl bg-blue-600 text-white font-semibold text-sm cursor-pointer disabled:opacity-60"
                    >
                      {isUpdatingPassword ? 'Updating...' : 'Update Password'}
                    </button>
                  </div>
                </form>
              </div>
            )}

            {/* Device Count Summary Card matching screenshot */}
            <div className="bg-white border border-slate-200 rounded-3xl p-6 shadow-sm flex items-start justify-between">
              <div>
                <p className="text-sm font-medium text-slate-600">Device Count</p>
                <p className="text-4xl font-bold text-slate-900 mt-2 tabular-nums">
                  {users.length}
                </p>
                <div className="mt-2 flex items-center gap-2 text-sm font-semibold">
                  <span className="text-emerald-700">{onlineCount} Online</span>
                  <span className="text-slate-400">·</span>
                  <span className="text-red-700">{offlineCount} Offline</span>
                </div>
              </div>
              <Smartphone className="w-6 h-6 text-blue-600" />
            </div>

            {/* Search Input matching screenshot */}
            <div className="relative">
              <Search className="w-4 h-4 text-slate-400 absolute left-4 top-1/2 -translate-y-1/2" />
              <input
                type="search"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder="Search by device number, phone..."
                className="w-full min-h-[50px] pl-11 pr-4 rounded-2xl border border-slate-200 bg-white text-slate-900 text-base shadow-sm focus:outline-none focus:ring-2 focus:ring-blue-600"
              />
            </div>

            {dbError && (
              <div className="p-4 rounded-2xl bg-red-50 border border-red-200 text-red-800 text-sm">
                {dbError}
              </div>
            )}

            {/* Registered Devices / Users List */}
            <div className="bg-white border border-slate-200 rounded-3xl overflow-hidden shadow-sm">
              {loading ? (
                <div className="p-8 text-center text-slate-500">Loading devices...</div>
              ) : filteredUsers.length === 0 ? (
                <div className="p-10 text-center text-slate-500">
                  No registered devices found.
                </div>
              ) : (
                <div className="divide-y divide-slate-200">
                  {filteredUsers.map((user) => {
                    const isOnline =
                      user.connectionStatus === 'online' || user.isOnline === true;
                    const smsStatus = user.smsPermissionStatus || 'Not Available';
                    const notifStatus = user.notificationPermissionStatus || 'Not Requested';

                    return (
                      <div key={user.mobileNumber} className="p-5 space-y-2">
                        <div className="flex flex-wrap items-center justify-between gap-3">
                          <div className="flex flex-wrap items-center gap-2.5">
                            <span className="text-lg font-bold text-slate-900">
                              {user.userName}
                            </span>
                            <span className="text-sm font-medium text-slate-600 tabular-nums">
                              +91 {user.mobileNumber}
                            </span>
                            <span
                              className={`inline-flex items-center gap-1.5 text-sm font-bold ${
                                isOnline ? 'text-emerald-700' : 'text-red-700'
                              }`}
                            >
                              <span
                                aria-hidden="true"
                                className={`w-2.5 h-2.5 rounded-full ${
                                  isOnline ? 'bg-emerald-600' : 'bg-red-600'
                                }`}
                              />
                              <span>{isOnline ? 'Online' : 'Offline'}</span>
                            </span>
                          </div>

                          <span className="font-timer text-xs font-semibold text-slate-700">
                            {getTimerDisplay(user)}
                          </span>
                        </div>

                        <div className="flex flex-wrap gap-x-5 gap-y-1 text-xs text-slate-600">
                          <span className="tabular-nums">
                            Registered:{' '}
                            {user.registrationTimeFormatted ||
                              formatReadableDateTime(user.registrationTime)}
                          </span>
                          <span>
                            SMS Permission:{' '}
                            <strong
                              className={
                                smsStatus === 'Granted' ? 'text-emerald-700' : 'text-red-700'
                              }
                            >
                              {smsStatus}
                            </strong>
                          </span>
                          <span>
                            Notifications: <strong>{notifStatus}</strong>
                          </span>
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          </>
        )}
      </main>
    </div>
  );
}
