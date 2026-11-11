"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import {
  getAccessToken,
  getActivities,
  getEntryOrThrow,
  getHiddenActivities,
  getHabits,
  patchEntry,
  saveEntry,
  type ActivityDef,
  type CheckInSaveResponse,
  type Entry,
  type HabitDef,
} from "@/lib/supabase/db";
import { useTranslation } from "@/lib/i18n";
import { pragueDateKey, shiftDateKey } from "@/lib/date";
import { notifyDataChanged } from "@/lib/data-refresh";
import { checkAndUnlockAchievements } from "@/lib/achievements";
import { getScalesOrThrow, seedDefaultScales, type Scale } from "@/lib/scales";
import type { CheckInData, CheckInPayload } from "@/lib/types";
import { isRecordedMood } from "@/lib/mood";
import {
  emptyCheckIn,
  fromDraft,
  fromEntry,
  hasContent,
  toDraft,
} from "@/lib/checkin/model";
import { buildCheckInPayload } from "@/lib/checkin/payload";
import { saveCheckIn } from "@/lib/checkin/save";
import { loadCheckIn } from "@/lib/checkin/load";
import {
  buildReflectionRequest,
  reconcileReflectionEntry,
  reflectionPatch,
} from "@/lib/checkin/readonly";
import { fetchGoalProgress } from "@/lib/goals-data";
import type { GoalProgressData } from "@/lib/goal-progress";
import type { Goal } from "@/lib/goals";
import {
  clampCheckInDate,
  isLateNight,
  isValidDateKey,
} from "@/lib/checkin/dates";
import {
  createDraftWriter,
  isDraftFlushDurableForNavigation,
  readDraftWithLock,
  readDraftConflictSnapshot,
  resolveDraftConflictCandidate,
  type DraftCandidateChoice,
  type DraftConflictSnapshotRead,
} from "@/lib/checkin/draft";
import {
  baselineFromEntry,
  clearPendingSave,
  createOrReuseSaveIntent,
  createPendingSaveRequest,
  newSaveRequestId,
  readPendingSaveAndDraft,
  releaseRejectedPendingSave,
  writePendingSave,
  type EntrySaveBaseline,
  type EntrySaveIntent,
  type PendingSaveRequest,
} from "@/lib/checkin/save-v2";
import {
  resolveDraftConflict,
  recoverOnlineLoad,
  rebaseCheckInData,
  type DraftConflict,
  type DraftConflictChoice,
} from "@/lib/checkin/draft-recovery";
import {
  pendingSaveMatchesForm,
  restorePendingSaveData,
} from "@/lib/checkin/save-recovery";
import {
  definitionKey,
  isDuplicateDefinition,
  mutateCatalogue,
} from "@/lib/checkin/catalogue";
import {
  createPhotoEditLifecycle,
  persistDraftBeforePhotoTrackingReset,
  photoSaveIntent,
} from "@/lib/checkin/photo-edit";
import {
  CheckInError,
  aiEmptyError,
  aiFailedError,
  aiNetworkError,
  aiPersistError,
  isCheckInError,
  isDefinitiveConditionalSaveRejection,
  toCheckInError,
} from "@/lib/checkin/errors";

import { CheckInForm } from "./CheckInForm";
import { CheckInHeader } from "./CheckInHeader";
import { StoredDayView } from "./StoredDayView";
import { DraftConflictPanel } from "./DraftConflictPanel";
import { DraftCandidateConflictPanel } from "./DraftCandidateConflictPanel";
import { PhotoViewer } from "./dialogs/PhotoViewer";
import { PostSaveReflectionDialog } from "./dialogs/PostSaveReflectionDialog";

/** JSON POST headers for the catalogue routes, with the session token if any. */
function authJsonHeaders(): Record<string, string> {
  const token = getAccessToken();
  return {
    "Content-Type": "application/json",
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  };
}

function resolveBrowserStorage(): Storage | null {
  if (typeof window === "undefined") return null;
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

function browserDraftLocksAvailable(): boolean {
  return typeof navigator !== "undefined" && typeof navigator.locks?.request === "function";
}

type ReadyDraftConflictSnapshot = Extract<DraftConflictSnapshotRead, { status: "ready" }>;

/**
 * Check-in orchestrator: date, load token, modes, save, draft and the
 * activity/habit/scale mutations. It renders no section internals — those live
 * in `sections/*` and are ordered by `CHECKIN_SECTION_ORDER`.
 *
 * Phase 2B renders the full Android beta5 order: mood → note, then the
 * read-only goals, screen-time and AI-reflection sections.
 */
export function CheckInScreen({
  userId,
  userName,
  onSaveDone,
  onOpenGoals,
  onOpenOverview,
  initialDate,
  localOnly = false,
  onRegisterDraftNavigationGuard,
}: {
  /** Provider-verified user ID, or explicit local workspace owner in localOnly mode. */
  userId: string;
  userName: string;
  onSaveDone: () => void;
  /** Opens the Goals sub-screen; goals are managed there, never here. */
  onOpenGoals: () => void;
  /** The post-save dialog's close action lands on Overview. */
  onOpenOverview: () => void;
  initialDate?: string | null;
  /** Prevent cloud access and expose only the owner-scoped local draft. */
  localOnly?: boolean;
  /** Register a shell-level navigation guard while this check-in is mounted. */
  onRegisterDraftNavigationGuard?: (guard: () => Promise<boolean>) => () => void;
}) {
  const { t, lang } = useTranslation();
  const today = pragueDateKey();
  // `?edit=DATE` is the explicit "open the form" signal; a plain Today visit
  // opens a recorded day passively.
  const openedInEdit = useRef(initialDate != null);

  const [data, setData] = useState<CheckInData>(emptyCheckIn);
  const dataRef = useRef(data);
  const [currentDate, setCurrentDate] = useState(() => clampCheckInDate(initialDate));
  const [reloadNonce, setReloadNonce] = useState(0);
  // `loading` is derived, not stored: an effect that sets it synchronously
  // would cascade renders, and a stale `false` can never flash an empty form
  // over a stored day.
  const requestKey = `${userId}:${localOnly ? "local" : "online"}:${currentDate}#${reloadNonce}`;
  const [loadedKey, setLoadedKey] = useState<string | null>(null);
  const loading = loadedKey !== requestKey;
  const [loadError, setLoadError] = useState<CheckInError | null>(null);
  const [saveError, setSaveError] = useState<CheckInError | null>(null);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [postSaveOpen, setPostSaveOpen] = useState(false);
  /** True from the first edit until the next successful load/save. */
  const [dirty, setDirty] = useState(false);
  const [editing, setEditing] = useState(initialDate != null);
  const [storedEntry, setStoredEntry] = useState<Entry | null>(null);
  const [saveBaseline, setSaveBaseline] = useState<EntrySaveBaseline | null>(null);
  const [draftConflict, setDraftConflict] = useState<DraftConflict<Entry> | null>(null);
  const [draftConflictError, setDraftConflictError] = useState<string | null>(null);
  const [draftCandidateConflict, setDraftCandidateConflict] = useState<ReadyDraftConflictSnapshot | null>(null);
  const [draftCandidateConflictError, setDraftCandidateConflictError] = useState<string | null>(null);
  const [unsavedDraftCopy, setUnsavedDraftCopy] = useState<string | null>(null);
  const [draftCopySucceeded, setDraftCopySucceeded] = useState(false);
  const [draftCopyFailed, setDraftCopyFailed] = useState(false);
  const [draftLockUnsupported, setDraftLockUnsupported] = useState(false);
  const [draftRecoveryNotice, setDraftRecoveryNotice] = useState(false);
  const [draftRestored, setDraftRestored] = useState(false);
  const [localDraftFound, setLocalDraftFound] = useState(false);
  const [draftStorageError, setDraftStorageError] = useState(false);
  const [draftStorageSaved, setDraftStorageSaved] = useState(false);
  const [pendingSave, setPendingSave] = useState<PendingSaveRequest | null>(null);
  const [pendingSaveNeedsReplay, setPendingSaveNeedsReplay] = useState(false);
  const [pendingSaveBlocked, setPendingSaveBlocked] = useState(false);
  const [lateNightHint, setLateNightHint] = useState(false);

  const [activityDefs, setActivityDefs] = useState<ActivityDef[]>([]);
  const [hiddenActivities, setHiddenActivities] = useState<ActivityDef[]>([]);
  const [activityError, setActivityError] = useState<string | null>(null);
  const [habitDefs, setHabitDefs] = useState<HabitDef[]>([]);
  const [habitError, setHabitError] = useState<string | null>(null);

  const [scales, setScales] = useState<Scale[]>([]);
  const [scalesError, setScalesError] = useState<string | null>(null);
  const [scalesNonce, setScalesNonce] = useState(0);
  const [scalesLoadedNonce, setScalesLoadedNonce] = useState(-1);
  const scalesLoading = !localOnly && scalesLoadedNonce !== scalesNonce;

  // Goals are a report for the selected date's calendar period; the check-in
  // never edits them. The key keeps "loading" derived so a failed read cannot
  // flash an empty-goals CTA over a slow one.
  const [goalRows, setGoalRows] = useState<GoalProgressData<Goal>[]>([]);
  const [goalsError, setGoalsError] = useState<string | null>(null);
  const [goalsNonce, setGoalsNonce] = useState(0);
  const [goalsLoadedKey, setGoalsLoadedKey] = useState<string | null>(null);
  const goalsKey = `${currentDate}#${goalsNonce}`;
  const goalsLoading = !localOnly && goalsLoadedKey !== goalsKey;

  // Reflection state is owned here, not by the section: generation must survive
  // a section collapse and must be invalidated when the date changes.
  const [reflectionLoading, setReflectionLoading] = useState(false);
  const [reflectionError, setReflectionError] = useState<CheckInError | null>(null);
  const [reflectionCached, setReflectionCached] = useState(false);

  const [photoUploading, setPhotoUploading] = useState(false);
  const [navigationPending, setNavigationPending] = useState(false);
  const [photoCleared, setPhotoCleared] = useState(false);
  const [viewingPhoto, setViewingPhoto] = useState<string | null>(null);
  const [photoLifecycle] = useState(() =>
    createPhotoEditLifecycle(
      process.env.NEXT_PUBLIC_SUPABASE_URL ?? ""
    )
  );
  const stagedPhotoByDate = useRef(new Map<string, string>());

  const saveInFlight = useRef(false);
  const saveIntentRef = useRef<EntrySaveIntent | null>(null);
  const pendingSaveRef = useRef<PendingSaveRequest | null>(null);
  const autoReplayAttemptRef = useRef<string | null>(null);
  const handleSaveRef = useRef<(request?: PendingSaveRequest) => Promise<void>>(
    async () => {}
  );
  const baselineDataRef = useRef<CheckInData | null>(null);
  const loadToken = useRef(0);
  const reflectionToken = useRef(0);
  const reflectionInFlight = useRef(false);
  const dateRef = useRef(currentDate);
  const ownerRef = useRef(userId);
  const navigationInFlight = useRef(false);
  const photoUploadingRef = useRef(false);
  const updatePhotoUploading = useCallback((uploading: boolean) => {
    photoUploadingRef.current = uploading;
    setPhotoUploading(uploading);
  }, []);
  const previousDateRef = useRef(currentDate);
  const lateNightChecked = useRef(false);
  const saveErrorRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    dataRef.current = data;
  }, [data]);
  // Draft writes are arbitrated by a synchronous flag, never by the `saved`
  // state: a cleanup closure captured before the save must not resurrect it.
  const draftWriter = useMemo(
    () =>
      createDraftWriter(
        () => (typeof window === "undefined" ? null : window.localStorage),
        userId
      ),
    [userId]
  );

  const flushDraft = useCallback(
    async (date: string, snapshot: CheckInData) => {
      const isActiveScope = () => ownerRef.current === userId && dateRef.current === date;
      const result = await draftWriter.flushWithStatus(date, snapshot);
      if (result.status === "conflict") {
        const conflicts = await readDraftConflictSnapshot(
          resolveBrowserStorage(),
          date,
          userId
        );
        if (conflicts.status === "ready" && conflicts.candidates.length > 0) {
          for (const candidate of conflicts.candidates) {
            const photoPath = candidate.data.photoPath;
            if (photoPath && photoLifecycle.track(userId, date, photoPath)) {
              photoLifecycle.preserve(userId, date, photoPath);
              stagedPhotoByDate.current.set(date, photoPath);
            }
          }
          if (!isActiveScope()) return result;
          setDraftCandidateConflict(conflicts);
          setDraftCandidateConflictError(null);
          setDraftStorageError(false);
          setDraftStorageSaved(false);
          setUnsavedDraftCopy(null);
          setDraftLockUnsupported(false);
          draftWriter.markUnedited();
        } else {
          if (!isActiveScope()) return result;
          setDraftStorageError(true);
          setDraftStorageSaved(false);
          setUnsavedDraftCopy(JSON.stringify({ date, data: toDraft(snapshot) }, null, 2));
          setDraftCopySucceeded(false);
          setDraftCopyFailed(false);
          return { status: "failed" as const };
        }
      } else if (result.status === "failed" || result.status === "unavailable") {
        if (!isActiveScope()) return result;
        setDraftStorageError(true);
        setDraftStorageSaved(false);
        setDraftLockUnsupported(result.status === "unavailable" && result.reason === "locks");
        setUnsavedDraftCopy(JSON.stringify({ date, data: toDraft(snapshot) }, null, 2));
        setDraftCopySucceeded(false);
        setDraftCopyFailed(false);
      } else if (result.status === "written") {
        if (snapshot.photoPath) photoLifecycle.preserve(userId, date, snapshot.photoPath);
        if (!isActiveScope()) return result;
        setDraftStorageError(false);
        setDraftStorageSaved(true);
        setUnsavedDraftCopy(null);
        setDraftLockUnsupported(false);
      } else if (result.status === "cleared") {
        if (!isActiveScope()) return result;
        setDraftStorageError(false);
        setDraftStorageSaved(false);
        setUnsavedDraftCopy(null);
        setDraftLockUnsupported(false);
      }
      return result;
    },
    [draftWriter, photoLifecycle, userId]
  );

  // Any edit invalidates the "saved in this session" card and re-enables drafts.
  const updateData = useCallback(
    (updater: (current: CheckInData) => CheckInData) => {
      draftWriter.markEdited();
      const next = updater(dataRef.current);
      dataRef.current = next;
      setData(next);
      if (draftStorageError || unsavedDraftCopy) {
        setUnsavedDraftCopy(JSON.stringify({ date: dateRef.current, data: toDraft(next) }, null, 2));
        setDraftCopySucceeded(false);
        setDraftCopyFailed(false);
      }
      setDraftStorageSaved(false);
      setSaved(false);
      setDirty(true);
      // The restored-draft / late-night banners describe the initial state;
      // once the user edits, they are stale.
      setDraftRestored(false);
      setLateNightHint(false);
    },
    [draftStorageError, draftWriter, unsavedDraftCopy]
  );

  const onPhotoSelected = useCallback(
    (url: string | null, uploadDate?: string) => {
      if (localOnly) return;
      const photoDate = uploadDate ?? dateRef.current;
      if (photoDate !== dateRef.current) {
        return;
      }
      const previousStaged = stagedPhotoByDate.current.get(photoDate) ?? null;

      if (url) {
        // `track` accepts only this owner's unique per-date staged path. An
        // unexpected URL is rejected instead of entering editable form state;
        // its remote object is retained because this browser cannot prove it unused.
        if (!photoLifecycle.track(userId, photoDate, url)) {
          return;
        }
        stagedPhotoByDate.current.set(photoDate, url);
        if (previousStaged && previousStaged !== url) {
          void photoLifecycle.discard(userId, photoDate, previousStaged);
        }
      } else {
        stagedPhotoByDate.current.delete(photoDate);
        if (previousStaged) {
          void photoLifecycle.discard(userId, photoDate, previousStaged);
        }
      }

      draftWriter.markEdited();
      setPhotoCleared(url === null);
      const next = {
        ...dataRef.current,
        photoPath: url,
        photoCleared: url === null,
        photoLegacy: url ? false : dataRef.current.photoLegacy,
      };
      dataRef.current = next;
      setData(next);
      if (draftStorageError || unsavedDraftCopy) {
        setUnsavedDraftCopy(JSON.stringify({ date: photoDate, data: toDraft(next) }, null, 2));
        setDraftCopySucceeded(false);
        setDraftCopyFailed(false);
      }
      setSaved(false);
      setDirty(true);
      setDraftRestored(false);
      setLateNightHint(false);
    },
    [draftStorageError, draftWriter, localOnly, photoLifecycle, unsavedDraftCopy, userId]
  );

  useEffect(() => {
    dateRef.current = currentDate;
  }, [currentDate]);

  useEffect(() => {
    const previousOwner = ownerRef.current;
    ownerRef.current = userId;
    if (previousOwner !== userId) {
      stagedPhotoByDate.current.clear();
      void photoLifecycle.abandonAll();
    }
  }, [photoLifecycle, userId]);

  useEffect(() => {
    const previousDate = previousDateRef.current;
    if (previousDate === currentDate) return;
    void photoLifecycle.abandonDate(previousDate);
    stagedPhotoByDate.current.delete(previousDate);
    previousDateRef.current = currentDate;
  }, [currentDate, photoLifecycle]);

  useEffect(
    () => () => {
      const date = dateRef.current;
      const snapshot = dataRef.current;
      void persistDraftBeforePhotoTrackingReset({
        persist: () => flushDraft(date, snapshot),
        ownerId: userId,
        date,
        photoUrl: snapshot.photoPath,
        preserve: (ownerId, photoDate, url) =>
          photoLifecycle.preserve(ownerId, photoDate, url),
        abandon: () => photoLifecycle.abandonAll(),
      });
      stagedPhotoByDate.current.clear();
    },
    [flushDraft, photoLifecycle, userId]
  );

  useEffect(() => {
    const mayLoseEdits =
      unsavedDraftCopy !== null ||
      (dirty && !draftStorageSaved && draftCandidateConflict === null);
    if (!mayLoseEdits) return;
    const warnBeforeLeaving = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", warnBeforeLeaving);
    return () => window.removeEventListener("beforeunload", warnBeforeLeaving);
  }, [dirty, draftCandidateConflict, draftStorageSaved, unsavedDraftCopy]);

  // ── Catalogue + session ──
  useEffect(() => {
    if (localOnly) return;
    getHabits()
      .then((defs) => {
        if (defs.length > 0) setHabitDefs(defs);
      })
      .catch((error) => console.error("Failed to load habits:", error));
    getActivities()
      .then((defs) => {
        if (defs.length > 0) setActivityDefs(defs);
      })
      .catch((error) => console.error("Failed to load activities:", error));
    getHiddenActivities()
      .then((defs) => {
        if (defs.length > 0) setHiddenActivities(defs);
      })
      .catch(() => {});
  }, [localOnly]);

  // ── Scales: read strictly; seed only when the account really has none ──
  useEffect(() => {
    if (localOnly) return;
    let cancelled = false;
    (async () => {
      try {
        const existing = await getScalesOrThrow();
        // A failed read throws before this point, so a network error can never
        // be mistaken for "no scales" and never triggers a seed.
        const list = existing.length === 0 ? await seedDefaultScales() : existing;
        if (!cancelled) {
          setScales(list);
          setScalesError(null);
        }
      } catch {
        if (!cancelled) setScalesError("checkin.scales_error");
      } finally {
        if (!cancelled) setScalesLoadedNonce(scalesNonce);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [localOnly, scalesNonce]);

  // ── Goals: read-only progress for the selected date's calendar period ──
  useEffect(() => {
    let cancelled = false;
    const key = goalsKey;
    if (localOnly) return;
    (async () => {
      try {
        const rows = await fetchGoalProgress(currentDate);
        if (cancelled) return;
        setGoalRows(rows);
        setGoalsError(null);
      } catch {
        // A failed read is not "no goals": keep the error and offer a retry.
        if (cancelled) return;
        setGoalRows([]);
        setGoalsError("checkin.goals_error");
      } finally {
        if (!cancelled) setGoalsLoadedKey(key);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [currentDate, goalsKey, localOnly]);

  // ── Entry load ──
  useEffect(() => {
    const token = ++loadToken.current;
    const key = requestKey;

    if (localOnly) {
      void readDraftConflictSnapshot(resolveBrowserStorage(), currentDate, userId).then((conflicts) => {
        if (token !== loadToken.current) return;
        if (conflicts.status !== "ready") {
          setLoadError(new CheckInError("load_failed", "checkin.error_draft_storage", 507));
          setDraftStorageError(true);
          setDraftStorageSaved(false);
          setDraftLockUnsupported(conflicts.status === "unavailable" && !browserDraftLocksAvailable());
          setDirty(false);
          dataRef.current = emptyCheckIn();
          setData(dataRef.current);
          setStoredEntry(null);
          setSaveBaseline(null);
          baselineDataRef.current = null;
          setEditing(true);
          setLoadedKey(key);
          return;
        }
        const draftRead = conflicts.current;
        draftWriter.observe(currentDate, draftRead);
        const draft = draftRead.status === "found" ? draftRead.data : null;
        const restored = Boolean(draft && hasContent(draft));
        for (const candidate of conflicts.candidates) {
          const photoPath = candidate.data.photoPath;
          if (photoPath && photoLifecycle.track(userId, currentDate, photoPath)) {
            photoLifecycle.preserve(userId, currentDate, photoPath);
            stagedPhotoByDate.current.set(currentDate, photoPath);
          }
        }
        const hasCandidateConflict = conflicts.candidates.length > 0;
        setLoadError(null);
        setSaveError(null);
        setDirty(restored || hasCandidateConflict);
        dataRef.current = draft ?? emptyCheckIn();
        setData(dataRef.current);
        setStoredEntry(null);
        setSaveBaseline(baselineFromEntry(null));
        baselineDataRef.current = emptyCheckIn();
        saveIntentRef.current = null;
        setDraftConflict(null);
        setDraftConflictError(null);
        setDraftCandidateConflict(hasCandidateConflict ? conflicts : null);
        setDraftCandidateConflictError(null);
        setUnsavedDraftCopy(null);
        setDraftCopySucceeded(false);
        setDraftCopyFailed(false);
        setDraftLockUnsupported(false);
        setDraftRecoveryNotice(false);
        setEditing(true);
        setDraftRestored(restored || hasCandidateConflict);
        setLocalDraftFound(restored || hasCandidateConflict);
        setDraftStorageError(false);
        setDraftStorageSaved(restored && !hasCandidateConflict);
        draftWriter.markUnedited();
        lateNightChecked.current = true;
        setLoadedKey(key);
      });
      return;
    }

    const storage = resolveBrowserStorage();
    void readPendingSaveAndDraft(storage, currentDate, userId).then((durableState) => {
      if (token !== loadToken.current) return;
      if (durableState.status === "ready" && durableState.conflicts.status !== "ready") {
        const storageError = new CheckInError("load_failed", "checkin.error_draft_storage", 507);
        setLoadError(storageError);
        setSaveError(storageError);
        setDraftStorageError(true);
        setDraftStorageSaved(false);
        setDraftLockUnsupported(
          durableState.conflicts.status === "unavailable" &&
            storage !== null &&
            !browserDraftLocksAvailable()
        );
        setData(emptyCheckIn());
        dataRef.current = emptyCheckIn();
        setStoredEntry(null);
        setSaveBaseline(null);
        baselineDataRef.current = null;
        setDraftConflict(null);
        setDraftCandidateConflict(null);
        draftWriter.markUnedited();
        setLoadedKey(key);
        return;
      }
      const conflictSnapshot =
        durableState.status === "ready" ? durableState.conflicts : null;
      if (conflictSnapshot?.status === "ready") {
        for (const candidate of conflictSnapshot.candidates) {
          const photoPath = candidate.data.photoPath;
          if (photoPath && photoLifecycle.track(userId, currentDate, photoPath)) {
            photoLifecycle.preserve(userId, currentDate, photoPath);
            stagedPhotoByDate.current.set(currentDate, photoPath);
          }
        }
      }
      const pendingRead =
        durableState.status === "ready"
          ? durableState.pending
          : { status: durableState.status };
      const draftRead =
        durableState.status === "ready"
          ? durableState.draft
          : { status: durableState.status };
      let pendingDisplayData: CheckInData | null = null;
      let pendingRecoveryBlocked = false;
      if (pendingRead.status === "found") {
        pendingSaveRef.current = pendingRead.request;
        draftWriter.observe(currentDate, draftRead);
        const restoredPending = restorePendingSaveData(
          pendingRead.request,
          draftRead
        );
      if (restoredPending.status === "blocked") {
        pendingRecoveryBlocked = true;
      } else {
        pendingDisplayData = restoredPending.data;
        const requestSnapshot = fromDraft(pendingRead.request.draftSnapshot);
        for (const photoPath of [
          requestSnapshot.photoPath,
          pendingDisplayData.photoPath,
        ]) {
          if (
            photoPath &&
            photoLifecycle.track(userId, currentDate, photoPath)
          ) {
            photoLifecycle.preserve(userId, currentDate, photoPath);
            stagedPhotoByDate.current.set(currentDate, photoPath);
          }
        }
      }
    } else {
      pendingSaveRef.current = null;
    }
    // Defer React state updates until after this effect finishes; the ref is
    // available immediately to preserve/replay the exact durable request.
    void Promise.resolve().then(() => {
      if (token !== loadToken.current) return;
      if (pendingRead.status === "found") {
        setPendingSave(pendingRead.request);
        setPendingSaveNeedsReplay(!pendingRecoveryBlocked);
        setPendingSaveBlocked(pendingRecoveryBlocked);
        setDraftStorageError(pendingRecoveryBlocked);
      } else if (pendingRead.status === "missing") {
        setPendingSave(null);
        setPendingSaveNeedsReplay(false);
        setPendingSaveBlocked(false);
        setDraftStorageError(false);
      } else {
        setPendingSave(null);
        setPendingSaveNeedsReplay(false);
        setPendingSaveBlocked(true);
        setDraftStorageError(true);
        setDraftStorageSaved(false);
        setDraftLockUnsupported(
          durableState.status === "unavailable" &&
            storage !== null &&
            !browserDraftLocksAvailable()
        );
      }
    });

    if (pendingRead.status === "invalid" || pendingRead.status === "failed" || pendingRead.status === "unavailable") {
      const errorCode =
        durableState.status !== "ready"
          ? "checkin.error_draft_storage"
          : "checkin.error_pending_record";
      const storageError = new CheckInError("load_failed", errorCode, 507);
      void Promise.resolve().then(() => {
        if (token !== loadToken.current) return;
        setLoadError(storageError);
        setSaveError(storageError);
        setDraftLockUnsupported(
          durableState.status === "unavailable" &&
            storage !== null &&
            !browserDraftLocksAvailable()
        );
        dataRef.current = emptyCheckIn();
        setData(dataRef.current);
        setStoredEntry(null);
        setSaveBaseline(null);
        baselineDataRef.current = null;
        setDraftConflict(null);
        setDraftConflictError(null);
        setDraftRecoveryNotice(false);
        draftWriter.markUnedited();
        setLoadedKey(key);
      });
      return;
    }

    if (pendingRecoveryBlocked) {
      const storageError = new CheckInError(
        "load_failed",
        "checkin.error_draft_storage",
        507
      );
      void Promise.resolve().then(() => {
        if (token !== loadToken.current) return;
        setLoadError(storageError);
        setSaveError(storageError);
        setPendingSaveNeedsReplay(false);
        setPendingSaveBlocked(true);
        setDraftStorageError(true);
        setDraftStorageSaved(false);
        setLoadedKey(key);
      });
      return;
    }

    if (
      pendingRead.status === "missing" &&
      conflictSnapshot?.status === "ready" &&
      conflictSnapshot.candidates.length > 0
    ) {
      const current = conflictSnapshot.current;
      draftWriter.observe(currentDate, current);
      dataRef.current = current.status === "found" ? current.data : emptyCheckIn();
      setData(dataRef.current);
      setLoadError(null);
      setSaveError(null);
      setStoredEntry(null);
      setSaveBaseline(null);
      baselineDataRef.current = null;
      setDraftConflict(null);
      setDraftConflictError(null);
      setDraftCandidateConflict(conflictSnapshot);
      setDraftCandidateConflictError(null);
      setDraftRecoveryNotice(false);
      setDraftStorageError(false);
      setDraftStorageSaved(false);
      setUnsavedDraftCopy(null);
      setDraftCopySucceeded(false);
      setDraftCopyFailed(false);
      setDraftLockUnsupported(false);
      setEditing(true);
      setDirty(true);
      draftWriter.markUnedited();
      setLoadedKey(key);
      return;
    }

    loadCheckIn(currentDate, getEntryOrThrow).then(async (outcome) => {
      if (token !== loadToken.current) return;
      // A freshly loaded row (or an empty form) is never an unsaved edit.
      setDirty(false);

      const recovery = await recoverOnlineLoad(
        outcome,
        resolveBrowserStorage(),
        currentDate,
        userId,
        (entry) =>
          fromEntry(entry as unknown as Record<string, unknown>)
      );
      if (recovery.status === "conflict") {
        draftWriter.observeRevision(currentDate, recovery.conflict.draftRevision);
      } else if (recovery.status !== "failed") {
        draftWriter.observeRevision(currentDate, recovery.draftRevision);
      }

      if (recovery.status === "failed") {
        // Never render an editable empty form over a stored day: a save would
        // overwrite it. Retry is the only way forward.
        setLoadError(recovery.error);
        setDraftStorageError(recovery.error.code === "checkin.error_draft_storage");
        setDraftStorageSaved(false);
        dataRef.current = emptyCheckIn();
        setData(dataRef.current);
        setStoredEntry(null);
        setSaveBaseline(null);
        baselineDataRef.current = null;
        saveIntentRef.current = null;
        setDraftConflict(null);
        setDraftConflictError(null);
        setDraftRecoveryNotice(false);
        setLoadedKey(key);
        return;
      }

      if (pendingRead.status === "found") {
        const pending = pendingRead.request;
        const pendingData =
          pendingDisplayData ?? fromDraft(pending.draftSnapshot);
        const cloudEntry = outcome.status === "entry" ? (outcome.entry as Entry) : null;
        const cloudData = cloudEntry
          ? fromEntry(cloudEntry as unknown as Record<string, unknown>)
          : emptyCheckIn();
        setLoadError(null);
        dataRef.current = pendingData;
        setData(pendingData);
        setStoredEntry(cloudEntry);
        setSaveBaseline(pending.baseline);
        baselineDataRef.current = cloudData;
        setDraftStorageSaved(false);
        saveIntentRef.current = null;
        setDraftCandidateConflict(null);
        setDraftCandidateConflictError(null);
        setUnsavedDraftCopy(null);
        setDraftCopySucceeded(false);
        setDraftCopyFailed(false);
        setDraftConflict({
          ownerId: userId,
          date: currentDate,
          draftRevision: draftWriter.revision(currentDate) ?? null,
          localDraft: pendingData,
          cloudEntry,
          cloudData,
          localIsFullSnapshot: true,
          ambiguous: true,
        });
        setDraftConflictError(null);
        setDraftRecoveryNotice(false);
        setEditing(true);
        setDirty(true);
        setDraftRestored(true);
        setLocalDraftFound(true);
        setPhotoCleared(pendingData.photoCleared);
        draftWriter.markEdited();
        lateNightChecked.current = true;
        setLoadedKey(key);
        return;
      }

      setLoadError(null);
      setDraftStorageError(false);
      if (recovery.status === "conflict") {
        // Keep both versions available and suppress draft flushes until the
        // user confirms which one to use. Loading alone is never a save.
        setDraftConflict(recovery.conflict);
        setDraftConflictError(null);
        setDraftCandidateConflict(null);
        setDraftCandidateConflictError(null);
        setUnsavedDraftCopy(null);
        setDraftCopySucceeded(false);
        setDraftCopyFailed(false);
        setDraftRecoveryNotice(false);
        dataRef.current = recovery.conflict.cloudData;
        setData(recovery.conflict.cloudData);
        const conflictEntry = recovery.conflict.cloudEntry as Entry;
        setStoredEntry(conflictEntry);
        setSaveBaseline(baselineFromEntry(conflictEntry));
        setPhotoCleared(false);
        baselineDataRef.current = recovery.conflict.cloudData;
        saveIntentRef.current = null;
        setEditing(true);
        setDraftRestored(false);
        draftWriter.markUnedited();
        lateNightChecked.current = true;
        setLoadedKey(key);
        return;
      }

      setDraftConflict(null);
      setDraftConflictError(null);
      setDraftCandidateConflict(null);
      setDraftCandidateConflictError(null);
      setUnsavedDraftCopy(null);
      setDraftCopySucceeded(false);
      setDraftCopyFailed(false);
      setDraftRecoveryNotice(false);
      if (recovery.status === "entry") {
        dataRef.current = recovery.data;
        setData(recovery.data);
        setDraftStorageSaved(false);
        setStoredEntry(recovery.entry as Entry);
        setSaveBaseline(baselineFromEntry(recovery.entry as Entry));
        setPhotoCleared(false);
        baselineDataRef.current = recovery.data;
        saveIntentRef.current = null;
        setEditing(openedInEdit.current);
        // A load is not a save. Do not clear any draft unless a successful
        // write is returned by the explicit save path.
        draftWriter.markUnedited();
      } else {
        setSaveBaseline(baselineFromEntry(null));
        baselineDataRef.current = emptyCheckIn();
        saveIntentRef.current = null;
        let restoredDraft = recovery.status === "draft" ? recovery.data : null;
        if (restoredDraft?.photoPath) {
          if (photoLifecycle.track(userId, currentDate, restoredDraft.photoPath)) {
            photoLifecycle.preserve(userId, currentDate, restoredDraft.photoPath);
            stagedPhotoByDate.current.set(currentDate, restoredDraft.photoPath);
          } else {
            // Old drafts could contain a stored or foreign URL. Only a validated
            // staged object may be restored into the editable form.
            restoredDraft = { ...restoredDraft, photoPath: null };
          }
        }
        dataRef.current = restoredDraft ?? emptyCheckIn();
        setData(dataRef.current);
        setStoredEntry(null);
        setPhotoCleared(Boolean(restoredDraft?.photoCleared));
        setDraftStorageSaved(Boolean(restoredDraft && hasContent(restoredDraft)));
        setEditing(true);
        if (restoredDraft && hasContent(restoredDraft)) {
          draftWriter.markEdited();
          setDirty(true);
        } else {
          draftWriter.markUnedited();
        }
        setDraftRestored(Boolean(restoredDraft && hasContent(restoredDraft)));
      }

      // Late-night step: only on a plain Today arrival, once, and only when
      // today has neither a recorded mood nor content.
      if (
        !lateNightChecked.current &&
        initialDate == null &&
        currentDate === today &&
        isLateNight() &&
        (outcome.status === "empty" ||
          (outcome.status === "entry" &&
            !isRecordedMood((outcome.entry as Entry).mood)))
      ) {
        const todayDraftRead = await readDraftWithLock(resolveBrowserStorage(), today, userId);
        if (todayDraftRead.status === "failed" || todayDraftRead.status === "unavailable") {
          const storageError = new CheckInError("load_failed", "checkin.error_draft_storage", 507);
          setLoadError(storageError);
          setSaveError(storageError);
          setDraftStorageError(true);
          setDraftStorageSaved(false);
          setLoadedKey(key);
          return;
        }
        const todayDraft = todayDraftRead.status === "found" ? todayDraftRead.data : null;
        if (!todayDraft || !hasContent(todayDraft)) {
          lateNightChecked.current = true;
          setLateNightHint(true);
          setCurrentDate(shiftDateKey(today, -1));
          return;
        }
      }
      lateNightChecked.current = true;
      setLoadedKey(key);
    });
    });
  }, [currentDate, requestKey, initialDate, today, draftWriter, photoLifecycle, userId, localOnly]);

  // Re-enable writes only after React has committed the selected local snapshot.
  // The previous effect cleanup must not flush its cloud snapshot in between.
  useEffect(() => {
    if (draftRecoveryNotice) draftWriter.markEdited();
  }, [draftRecoveryNotice, draftWriter]);

  // ── Draft: local only, 1,200 ms, flushed on hide/unmount ──
  useEffect(() => {
    if (saved || loading || loadError) return;
    const timer = setTimeout(() => {
      flushDraft(currentDate, data);
    }, 1200);
    return () => clearTimeout(timer);
  }, [data, saved, loading, loadError, currentDate, draftWriter, flushDraft]);

  useEffect(() => {
    if (saved || loading || loadError) return;
    const flush = () => {
      flushDraft(currentDate, data);
    };
    const onVisibility = () => {
      if (document.visibilityState === "hidden") flush();
    };
    window.addEventListener("pagehide", flush);
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      window.removeEventListener("pagehide", flush);
      document.removeEventListener("visibilitychange", onVisibility);
      // This cleanup still closes over the pre-save render when `saved` flips,
      // so the guard lives in the writer: a successful save always wins.
      flush();
    };
  }, [data, saved, loading, loadError, currentDate, draftWriter, flushDraft]);

  useEffect(() => {
    if (saveError) saveErrorRef.current?.focus();
  }, [saveError]);

  const persistCurrentDraftForNavigation = useCallback(async () => {
    const date = currentDate;
    const owner = userId;
    const snapshot = dataRef.current;
    const existingConflictIsDurable =
      draftCandidateConflict?.status === "ready" &&
      draftCandidateConflict.ownerId === owner &&
      draftCandidateConflict.date === date &&
      draftCandidateConflict.candidates.length > 0;
    if (existingConflictIsDurable) {
      if (snapshot.photoPath) photoLifecycle.preserve(owner, date, snapshot.photoPath);
      return ownerRef.current === owner && dateRef.current === date;
    }

    const result = await flushDraft(date, snapshot);
    const durable = isDraftFlushDurableForNavigation(result, { ownerId: owner, date });
    if (!durable || ownerRef.current !== owner || dateRef.current !== date) return false;
    if (snapshot.photoPath) photoLifecycle.preserve(owner, date, snapshot.photoPath);
    return true;
  }, [currentDate, draftCandidateConflict, flushDraft, photoLifecycle, userId]);

  const handleBeforeShellNavigation = useCallback(async () => {
    if (navigationInFlight.current || photoUploadingRef.current) return false;
    navigationInFlight.current = true;
    setNavigationPending(true);
    const date = currentDate;
    const owner = userId;
    const snapshot = dataRef.current;
    try {
      return await persistCurrentDraftForNavigation();
    } catch {
      if (ownerRef.current === owner && dateRef.current === date) {
        setDraftStorageError(true);
        setDraftStorageSaved(false);
        setUnsavedDraftCopy(JSON.stringify({ date, data: toDraft(snapshot) }, null, 2));
        setDraftCopySucceeded(false);
        setDraftCopyFailed(false);
      }
      return false;
    } finally {
      navigationInFlight.current = false;
      setNavigationPending(false);
    }
  }, [currentDate, persistCurrentDraftForNavigation, userId]);

  useEffect(() => {
    if (!onRegisterDraftNavigationGuard) return;
    return onRegisterDraftNavigationGuard(handleBeforeShellNavigation);
  }, [handleBeforeShellNavigation, onRegisterDraftNavigationGuard]);

  const handleCopyUnsavedDraft = async () => {
    if (!unsavedDraftCopy) return;
    setDraftCopySucceeded(false);
    setDraftCopyFailed(false);
    try {
      if (typeof navigator === "undefined" || !navigator.clipboard?.writeText) {
        throw new Error("Clipboard API unavailable");
      }
      await navigator.clipboard.writeText(unsavedDraftCopy);
      setDraftCopySucceeded(true);
    } catch {
      setDraftCopyFailed(true);
    }
  };

  const handleRetryDraftStorage = async () => {
    if (navigationInFlight.current || photoUploadingRef.current) return;
    navigationInFlight.current = true;
    setNavigationPending(true);
    const date = currentDate;
    const owner = userId;
    const pageSnapshot = dataRef.current;
    const unsupportedLocks = !browserDraftLocksAvailable();
    const isActiveScope = () => ownerRef.current === owner && dateRef.current === date;
    try {
      const storage = resolveBrowserStorage();
      if (!storage) {
        setDraftStorageError(true);
        setDraftLockUnsupported(false);
        return;
      }
      let snapshot = await readDraftConflictSnapshot(storage, date, owner);
      if (!isActiveScope()) return;
      if (snapshot.status !== "ready") {
        setDraftStorageError(true);
        setDraftLockUnsupported(snapshot.status === "unavailable" && unsupportedLocks);
        return;
      }

      const hasUnsavedPageSnapshot =
        unsavedDraftCopy !== null || (dirty && !draftStorageSaved);
      let pageSnapshotPersisted = false;
      if (snapshot.candidates.length > 0 && hasUnsavedPageSnapshot) {
        // Preserve this tab's in-memory edits before replacing the form with a
        // panel for older durable candidates. A changed revision becomes its
        // own candidate instead of silently losing this page's snapshot.
        draftWriter.markEdited();
        const write = await flushDraft(date, pageSnapshot);
        if (!isActiveScope()) return;
        if (write.status === "failed" || write.status === "unavailable") return;
        snapshot = await readDraftConflictSnapshot(storage, date, owner);
        if (!isActiveScope()) return;
        const pageSnapshotJson = JSON.stringify(toDraft(pageSnapshot));
        const pageSnapshotMatches = snapshot.status === "ready" && (
          snapshot.current.status === "found" &&
            JSON.stringify(toDraft(snapshot.current.data)) === pageSnapshotJson ||
          snapshot.current.status === "missing" && !hasContent(pageSnapshot) ||
          snapshot.candidates.some(
            (candidate) => JSON.stringify(toDraft(candidate.data)) === pageSnapshotJson
          )
        );
        if (!pageSnapshotMatches) {
          setDraftStorageError(true);
          setDraftStorageSaved(false);
          setUnsavedDraftCopy(JSON.stringify({ date, data: toDraft(pageSnapshot) }, null, 2));
          setDraftCopySucceeded(false);
          setDraftCopyFailed(false);
          setDraftLockUnsupported(snapshot.status === "unavailable" && unsupportedLocks);
          return;
        }
        pageSnapshotPersisted = true;
      }

      if (snapshot.status !== "ready") {
        setDraftStorageError(true);
        setDraftStorageSaved(false);
        setDraftLockUnsupported(snapshot.status === "unavailable" && unsupportedLocks);
        return;
      }
      if (snapshot.candidates.length > 0) {
        for (const candidate of snapshot.candidates) {
          const photoPath = candidate.data.photoPath;
          if (photoPath && photoLifecycle.track(owner, date, photoPath)) {
            photoLifecycle.preserve(owner, date, photoPath);
            stagedPhotoByDate.current.set(date, photoPath);
          }
        }
        setDraftCandidateConflict(snapshot);
        setDraftCandidateConflictError(null);
        setDraftStorageError(false);
        setDraftStorageSaved(false);
        setUnsavedDraftCopy(null);
        setDraftCopySucceeded(false);
        setDraftCopyFailed(false);
        setDraftLockUnsupported(false);
        draftWriter.markUnedited();
        return;
      }

      if (pageSnapshotPersisted) {
        setDraftCandidateConflict(null);
        setDraftCandidateConflictError(null);
        setDraftStorageError(false);
        setDraftStorageSaved(true);
        setUnsavedDraftCopy(null);
        setDraftCopySucceeded(false);
        setDraftCopyFailed(false);
        setDraftLockUnsupported(false);
        draftWriter.markUnedited();
        return;
      }

      // Keep the writer's original expected revision. If another tab changed the
      // active draft while storage was unavailable, this retry creates a conflict
      // candidate rather than adopting and overwriting the newer revision.
      draftWriter.markEdited();
      await flushDraft(date, pageSnapshot);
    } catch {
      if (isActiveScope()) {
        setDraftStorageError(true);
        setDraftStorageSaved(false);
        setUnsavedDraftCopy(JSON.stringify({ date, data: toDraft(pageSnapshot) }, null, 2));
        setDraftCopySucceeded(false);
        setDraftCopyFailed(false);
        setDraftLockUnsupported(unsupportedLocks);
      }
    } finally {
      navigationInFlight.current = false;
      setNavigationPending(false);
    }
  };

  // ── Date navigation ──
  const navigateTo = useCallback(
    async (next: string) => {
      const clamped = clampCheckInDate(next, today);
      if (clamped === currentDate || navigationInFlight.current) return;
      navigationInFlight.current = true;
      setNavigationPending(true);
      const date = currentDate;
      const owner = userId;
      const snapshot = dataRef.current;
      try {
        // A date change is allowed only after a durable local write or a verified
        // conflict record. Pagehide remains best-effort and is not this guarantee.
        if (!(await persistCurrentDraftForNavigation())) return;
        if (ownerRef.current !== owner || dateRef.current !== date) return;
        void photoLifecycle.abandonDate(date);
        stagedPhotoByDate.current.delete(date);
        dateRef.current = clamped;
        previousDateRef.current = clamped;
        // Every per-day bit of state resets only after the previous snapshot has
        // become durable; a failed write leaves this screen and its copy available.
        setSaveError(null);
        setLoadError(null);
        setSaveBaseline(null);
        baselineDataRef.current = null;
        saveIntentRef.current = null;
        setSaved(false);
        setDirty(false);
        updatePhotoUploading(false);
        setPhotoCleared(false);
        setDraftRestored(false);
        setDraftConflict(null);
        setDraftConflictError(null);
        setDraftCandidateConflict(null);
        setDraftCandidateConflictError(null);
        setDraftRecoveryNotice(false);
        setLateNightHint(false);
        setPostSaveOpen(false);
        // Invalidate an in-flight reflection: its response belongs to the old day
        // and must never be rendered (or patched) under the new date.
        reflectionToken.current += 1;
        reflectionInFlight.current = false;
        setReflectionLoading(false);
        setReflectionError(null);
        setReflectionCached(false);
        setCurrentDate(clamped);
      } catch {
        if (ownerRef.current === owner && dateRef.current === date) {
          setDraftStorageError(true);
          setDraftStorageSaved(false);
          setUnsavedDraftCopy(JSON.stringify({ date, data: toDraft(snapshot) }, null, 2));
          setDraftCopySucceeded(false);
          setDraftCopyFailed(false);
        }
      } finally {
        navigationInFlight.current = false;
        setNavigationPending(false);
      }
    },
    [
      currentDate,
      persistCurrentDraftForNavigation,
      photoLifecycle,
      today,
      updatePhotoUploading,
      userId,
    ]
  );

  const handleDraftConflictResolution = async (choice: DraftConflictChoice) => {
    if (!draftConflict) return;
    if (draftConflict.ambiguous && choice === "local") {
      // Reuse the immutable request ID and exact save snapshot. CAS idempotency
      // resolves the lost response without issuing a new write over newer data.
      setDraftConflictError(null);
      void handleSave(pendingSaveRef.current ?? pendingSave ?? undefined);
      return;
    }
    if (draftConflict.ambiguous && choice === "cloud") {
      // This is not a cloud-selection operation: keep the local form and draft,
      // close the status panel, and let any later save use the same CAS baseline.
      setDraftConflict(null);
      setDraftConflictError(null);
      setSaveError(null);
      draftWriter.markEdited();
      setDirty(true);
      setDraftRestored(true);
      setDraftRecoveryNotice(true);
      return;
    }
    const storage = resolveBrowserStorage();
    if (!storage) {
      setDraftConflictError("checkin.draft_conflict_storage_error");
      setDraftStorageError(true);
      return;
    }
    const unresolved = pendingSaveRef.current;
    if (
      unresolved &&
      (unresolved.ownerId !== userId || unresolved.date !== currentDate)
    ) {
      setDraftConflictError("checkin.draft_conflict_stale_error");
      return;
    }
    if (
      unresolved &&
      !(await clearPendingSave(storage, currentDate, userId, unresolved.requestId))
    ) {
      setDraftConflictError("checkin.draft_conflict_storage_error");
      setDraftStorageError(true);
      return;
    }
    if (unresolved) {
      pendingSaveRef.current = null;
      setPendingSave(null);
      setPendingSaveNeedsReplay(false);
      setPendingSaveBlocked(false);
    }
    const result = await resolveDraftConflict({
      storage,
      conflict: draftConflict,
      choice,
      confirmed: true,
      activeOwnerId: userId,
      activeDate: currentDate,
    });

    if (result.status === "storage_failed") {
      setDraftConflictError("checkin.draft_conflict_storage_error");
      return;
    }
    if (result.status === "stale" || result.status === "confirmation_required") {
      setDraftConflictError("checkin.draft_conflict_stale_error");
      return;
    }
    if (result.status === "cloud") {
      draftWriter.observeRevision(currentDate, result.revision);
    }

    setDraftConflict(null);
    setDraftConflictError(null);
    dataRef.current = result.data;
    setData(result.data);
    setStoredEntry(result.entry as Entry | null);
    setSaveBaseline(baselineFromEntry(result.entry));
    baselineDataRef.current = draftConflict.cloudData;
    setSaved(false);
    setSaveError(null);
    if (result.status === "local") {
      setPhotoCleared(result.data.photoCleared);
      if (!draftConflict.ambiguous) saveIntentRef.current = null;
      // Keep the existing draft through stale effect cleanup; a following effect
      // enables writes only after this selected local snapshot has committed.
      draftWriter.markUnedited();
      setEditing(true);
      setDirty(true);
      setDraftRestored(true);
      setDraftRecoveryNotice(true);
      return;
    }

    saveIntentRef.current = null;
    setPhotoCleared(result.data.photoCleared);
    draftWriter.markUnedited();
    setEditing(openedInEdit.current);
    setDirty(false);
    setDraftRestored(false);
    setDraftRecoveryNotice(false);
  };

  const handleDraftCandidateResolution = async (choice: DraftCandidateChoice) => {
    const shown = draftCandidateConflict;
    const candidate = shown?.candidates[0];
    if (!shown || !candidate) return;
    const isActiveScope = () =>
      ownerRef.current === candidate.ownerId && dateRef.current === candidate.date;
    if (candidate.ownerId !== userId || candidate.date !== currentDate || !isActiveScope()) return;
    const storage = resolveBrowserStorage();
    if (!storage) {
      setDraftCandidateConflictError("checkin.draft_candidate_storage_error");
      setDraftStorageError(true);
      return;
    }

    const result = await resolveDraftConflictCandidate({
      storage,
      ownerId: userId,
      date: currentDate,
      conflict: candidate,
      expectedCurrentRevision: shown.current.revision ?? null,
      choice,
      confirmed: true,
    });
    if (!isActiveScope()) return;

    if (result.status === "storage_failed") {
      setDraftCandidateConflictError("checkin.draft_candidate_storage_error");
      setDraftStorageError(true);
      setDraftStorageSaved(false);
      return;
    }
    if (result.status === "stale" || result.status === "confirmation_required") {
      const refreshed =
        result.status === "stale" && result.snapshot?.status === "ready"
          ? result.snapshot
          : await readDraftConflictSnapshot(storage, currentDate, userId);
      if (!isActiveScope()) return;
      if (refreshed.status === "ready" && refreshed.candidates.length > 0) {
        for (const item of refreshed.candidates) {
          const photoPath = item.data.photoPath;
          if (photoPath && photoLifecycle.track(userId, currentDate, photoPath)) {
            photoLifecycle.preserve(userId, currentDate, photoPath);
            stagedPhotoByDate.current.set(currentDate, photoPath);
          }
        }
        setDraftCandidateConflict(refreshed);
        setDraftCandidateConflictError("checkin.draft_candidate_stale_error");
      } else if (refreshed.status === "ready") {
        setDraftCandidateConflict(null);
        setDraftCandidateConflictError(null);
        setReloadNonce((value) => value + 1);
      } else {
        setDraftCandidateConflictError("checkin.draft_candidate_storage_error");
        setDraftStorageError(true);
      }
      return;
    }

    if (candidate.data.photoPath) {
      if (choice === "candidate") {
        // The selected candidate is now the durable active owner/date draft.
        // Protect it before any later cleanup can see the former candidate slot
        // disappear. Re-register if this lifecycle did not track the snapshot.
        if (!photoLifecycle.preserve(userId, currentDate, candidate.data.photoPath)) {
          if (photoLifecycle.track(userId, currentDate, candidate.data.photoPath)) {
            photoLifecycle.preserve(userId, currentDate, candidate.data.photoPath);
          }
        }
      } else {
        // Cleanup performs a locked durable-reference scan and retains the URL
        // on unreadable/ambiguous storage or when any other draft still uses it.
        await photoLifecycle.discard(userId, currentDate, candidate.data.photoPath);
        if (!isActiveScope()) return;
      }
    }
    draftWriter.observeRevision(currentDate, result.revision);
    const refreshed = await readDraftConflictSnapshot(storage, currentDate, userId);
    if (!isActiveScope()) return;
    if (refreshed.status !== "ready") {
      setDraftCandidateConflictError("checkin.draft_candidate_storage_error");
      setDraftStorageError(true);
      setDraftStorageSaved(false);
      setReloadNonce((value) => value + 1);
      return;
    }
    if (refreshed.candidates.length > 0) {
      setDraftCandidateConflict(refreshed);
      setDraftCandidateConflictError(null);
      return;
    }

    setDraftCandidateConflict(null);
    setDraftCandidateConflictError(null);
    setDraftStorageError(false);
    setDraftStorageSaved(false);
    setUnsavedDraftCopy(null);
    setDraftLockUnsupported(false);
    // Re-load the cloud baseline and active draft before exposing editing or Save.
    setReloadNonce((value) => value + 1);
  };

  const shiftDay = (offset: number) => {
    if (saving || photoUploading || navigationPending) return;
    const next = shiftDateKey(currentDate, offset);
    if (next > today) return;
    navigateTo(next);
  };

  const pickDate = (value: string) => {
    if (saving || photoUploading || navigationPending) return;
    if (!isValidDateKey(value)) return;
    navigateTo(value);
  };

  // ── Save ──
  const handleSave = async (requestOverride?: PendingSaveRequest) => {
    if (
      localOnly ||
      loadError ||
      !saveBaseline ||
      loadedKey !== requestKey ||
      (draftConflict && !draftConflict.ambiguous) ||
      pendingSaveBlocked
    ) return;
    const saveDate = requestOverride?.date ?? currentDate;
    if (
      saveDate !== currentDate ||
      (requestOverride &&
        (requestOverride.ownerId !== userId || requestOverride.date !== saveDate))
    ) {
      setSaveError(new CheckInError("save_failed", "checkin.error_pending_record", 507));
      setDraftStorageError(true);
      return;
    }

    const storage = resolveBrowserStorage();
    const durableState = await readPendingSaveAndDraft(storage, saveDate, userId);
    if (durableState.status !== "ready") {
      setDraftStorageError(true);
      setDraftStorageSaved(false);
      setPendingSaveBlocked(Boolean(requestOverride ?? pendingSaveRef.current));
      setSaveError(new CheckInError("save_failed", "checkin.error_draft_storage", 507));
      return;
    }
    const storedRead = durableState.pending;
    if (
      storedRead.status === "invalid" ||
      storedRead.status === "failed" ||
      storedRead.status === "unavailable"
    ) {
      setDraftStorageError(true);
      setDraftStorageSaved(false);
      setPendingSaveBlocked(storedRead.status === "invalid");
      setSaveError(new CheckInError("save_failed", "checkin.error_pending_storage", 507));
      return;
    }
    if (
      durableState.draft.status === "failed" ||
      durableState.draft.status === "unavailable"
    ) {
      setPendingSaveBlocked(true);
      setDraftStorageError(true);
      setDraftStorageSaved(false);
      setSaveError(new CheckInError("save_failed", "checkin.error_draft_storage", 507));
      return;
    }

    let pendingRequest = requestOverride ?? pendingSaveRef.current;
    if (
      pendingRequest &&
      (pendingRequest.ownerId !== userId || pendingRequest.date !== saveDate)
    ) {
      setPendingSaveBlocked(true);
      setSaveError(new CheckInError("save_failed", "checkin.error_pending_record", 507));
      return;
    }
    if (storedRead.status === "found") {
      if (pendingRequest && (storedRead.request.requestId !== pendingRequest.requestId || storedRead.request.body !== pendingRequest.body)) {
        setPendingSaveBlocked(true);
        setDraftStorageError(true);
        setSaveError(new CheckInError("save_failed", "checkin.error_pending_storage", 507));
        return;
      }
      pendingRequest ??= storedRead.request;
    }

    const photoIntent = pendingRequest
      ? {
          ...(typeof pendingRequest.payload.photo_path === "string"
            ? { photo_path: pendingRequest.payload.photo_path }
            : {}),
          ...(pendingRequest.payload.clear_photo === true ? { clear_photo: true } : {}),
        }
      : photoSaveIntent({
          stored: storedEntry?.photo_path ?? null,
          staged: data.photoPath,
          cleared: photoCleared,
        });
    const intentBaseline = pendingRequest?.baseline ?? saveBaseline;
    const payload: CheckInPayload = pendingRequest
      ? (pendingRequest.payload as unknown as CheckInPayload)
      : buildCheckInPayload(data, saveDate, {
          clearPhoto: photoIntent.clear_photo === true,
        });

    let draftPreflightFailed = false;
    if (!pendingRequest) {
      try {
        const saveIntent = createOrReuseSaveIntent(
          payload as unknown as Record<string, unknown>,
          intentBaseline,
          saveIntentRef.current,
          newSaveRequestId
        );
        saveIntentRef.current = saveIntent;
        const draftFlush = await draftWriter.flushWithStatus(saveDate, data);
        draftPreflightFailed = !["written", "cleared", "skipped"].includes(draftFlush.status);
        if (draftPreflightFailed) {
          setDraftStorageError(true);
          setDraftStorageSaved(false);
        }
        const draftRevision =
          draftFlush.status === "written" || draftFlush.status === "cleared"
            ? draftFlush.revision
            : draftWriter.revision(saveDate);
        pendingRequest = createPendingSaveRequest({
          ownerId: userId,
          date: saveDate,
          baseline: intentBaseline,
          requestId: saveIntent.requestId,
          createdAt: draftFlush.status === "written" ? draftFlush.updatedAt : Date.now(),
          draftRevision,
          payload,
          draftSnapshot: toDraft(data),
        });
      } catch (error) {
        setSaveError(toCheckInError(error));
        return;
      }
    }

    const durableRequest = pendingRequest;
    if (!durableRequest) return;
    if (!storage || !(await writePendingSave(storage, durableRequest))) {
      setDraftStorageError(true);
      setDraftStorageSaved(false);
      setSaveError(new CheckInError("save_failed", "checkin.error_pending_storage", 507));
      return;
    }

    pendingSaveRef.current = durableRequest;
    setPendingSave(durableRequest);
    setPendingSaveNeedsReplay(false);
    setPendingSaveBlocked(false);
    draftWriter.markUnedited();
    setDraftStorageError(draftPreflightFailed);
    if (typeof durableRequest.payload.photo_path === "string") {
      photoLifecycle.preserve(userId, saveDate, durableRequest.payload.photo_path);
    }

    const photoSaveLease = photoLifecycle.beginSave(
      userId,
      saveDate,
      typeof durableRequest.payload.photo_path === "string"
        ? durableRequest.payload.photo_path
        : null
    );
    let photoLeaseSettled = false;
    const settlePhotoLease = async (committed: boolean | "unknown") => {
      if (!photoSaveLease || photoLeaseSettled) return;
      photoLeaseSettled = true;
      await photoSaveLease.finish(committed);
      if (committed) stagedPhotoByDate.current.delete(saveDate);
    };

    setSaveError(null);
    setSaving(true);
    try {
      const result = await saveCheckIn({
        gate: {
          loadFailed: Boolean(loadError),
          photoUploading,
          // Removing the only stored photo is a real edit even though the
          // remaining form is empty; otherwise the removal could never save.
          hasContent:
            hasContent(fromDraft(durableRequest.draftSnapshot)) ||
            durableRequest.payload.clear_photo === true,
        },
        inFlight: saveInFlight,
        payload,
        post: () => saveEntry(durableRequest),
      });

      if (result.status === "failed") {
        const isConflict = result.error.kind === "conflict" || result.error.status === 409;
        const definitivelyRejected = isDefinitiveConditionalSaveRejection(result.error);
        const isAmbiguous =
          !definitivelyRejected &&
          (result.error.code === "checkin.error_save_network" ||
            result.error.status >= 500);
        await settlePhotoLease(isAmbiguous ? "unknown" : false);

        if (isConflict) {
          const cause = result.error.cause;
          const response =
            cause && typeof cause === "object" && !Array.isArray(cause)
              ? (cause as Record<string, unknown>)
              : null;
          let latest: Entry | null | undefined;
          if (response && "latest" in response && response.latest_read_failed !== true) {
            latest = response.latest as Entry | null;
          }
          if (latest === undefined) {
            try {
              latest = await getEntryOrThrow(saveDate);
            } catch {
              setSaveError(result.error);
              return;
            }
          }
          if (latest && (latest.user_id !== userId || latest.date !== saveDate)) {
            setSaveError(result.error);
            return;
          }

          let latestBaseline: EntrySaveBaseline;
          try {
            latestBaseline = baselineFromEntry(latest);
          } catch {
            setSaveError(result.error);
            return;
          }
          const cloudData = latest
            ? fromEntry(latest as unknown as Record<string, unknown>)
            : emptyCheckIn();
          const baseData = baselineDataRef.current ?? emptyCheckIn();
          const localData = pendingRequest
            ? data
            : rebaseCheckInData(data, baseData, cloudData);
          dataRef.current = localData;
          setData(localData);
          setStoredEntry(latest);
          setSaveBaseline(latestBaseline);
          setPhotoCleared(localData.photoCleared);
          baselineDataRef.current = cloudData;
          if (!pendingRequest) saveIntentRef.current = null;
          setDraftConflict({
            ownerId: userId,
            date: saveDate,
            draftRevision: draftWriter.revision(saveDate) ?? null,
            localDraft: localData,
            cloudEntry: latest,
            cloudData,
            localIsFullSnapshot: true,
          });
          setDraftConflictError(null);
          setDraftRecoveryNotice(false);
          setSaved(false);
          setDirty(false);
          setEditing(true);
          setDraftRestored(true);
          setSaveError(result.error);
          return;
        }

        if (isAmbiguous) {
          try {
            const latest = await getEntryOrThrow(saveDate);
            if (latest && (latest.user_id !== userId || latest.date !== saveDate)) {
              throw new Error("Latest row does not match the active owner/date");
            }
            // Validate the display read, but deliberately keep the original
            // baseline and request ID until the user retries or edits.
            baselineFromEntry(latest);
            const cloudData = latest
              ? fromEntry(latest as unknown as Record<string, unknown>)
              : emptyCheckIn();
            const localData = data;
            setDraftConflict({
              ownerId: userId,
              date: saveDate,
              draftRevision: durableRequest.draftRevision ?? null,
              localDraft: localData,
              cloudEntry: latest,
              cloudData,
              localIsFullSnapshot: true,
              ambiguous: true,
            });
            setDraftConflictError(null);
            setSaveError(result.error);
          } catch {
            // The latest read is display-only. If it fails, retain the draft
            // and original CAS token; retrying the same request remains safe.
            setSaveError(result.error);
          }
          return;
        }

        if (definitivelyRejected) {
          // The server definitively rejected this request, so it cannot commit
          // later. Demote its form snapshot to the ordinary draft before
          // releasing the immutable request ID; otherwise every future Save
          // would replay the same rejected bytes forever.
          draftWriter.markEdited();
          const release = await releaseRejectedPendingSave(storage, durableRequest, data);
          if (release.status === "released") {
            pendingSaveRef.current = null;
            setPendingSave(null);
            setPendingSaveNeedsReplay(false);
            setPendingSaveBlocked(false);
            saveIntentRef.current = null;
            draftWriter.observeRevision(saveDate, release.revision);
            dataRef.current = release.data;
            setData(release.data);
            setPhotoCleared(release.data.photoCleared);
            if (release.data.photoPath && photoLifecycle.track(userId, saveDate, release.data.photoPath)) {
              photoLifecycle.preserve(userId, saveDate, release.data.photoPath);
            }
            setDraftStorageError(false);
            setDraftStorageSaved(hasContent(release.data));
            setDirty(true);
          } else {
            setPendingSaveBlocked(true);
            setDraftStorageError(true);
            setDraftStorageSaved(false);
          }
          setSaveError(result.error);
          return;
        }

        setSaveError(result.error);
        return;
      }
      if (result.status !== "saved") {
        await settlePhotoLease(false);
        return;
      }

      const saveResponse = result.entry as CheckInSaveResponse;
      let returnedEntry = saveResponse.entry;
      if (
        returnedEntry.user_id !== userId ||
        returnedEntry.date !== saveDate
      ) {
        await settlePhotoLease("unknown");
        setSaveError(new CheckInError("save_failed", "checkin.error_save_generic", 503));
        return;
      }

      if (saveResponse.replayed) {
        // A receipt is historical. Re-read the scoped row and clear local
        // recovery data only if its current identity/revision still matches.
        let latest: Entry | null;
        try {
          latest = await getEntryOrThrow(saveDate);
        } catch (error) {
          await settlePhotoLease("unknown");
          setSaveError(toCheckInError(error));
          return;
        }
        if (
          !latest ||
          latest.user_id !== userId ||
          latest.date !== saveDate ||
          latest.id !== returnedEntry.id ||
          latest.revision !== returnedEntry.revision
        ) {
          await settlePhotoLease("unknown");
          const cloudData = latest
            ? fromEntry(latest as unknown as Record<string, unknown>)
            : emptyCheckIn();
          setStoredEntry(latest);
          setSaveBaseline(baselineFromEntry(latest));
          baselineDataRef.current = cloudData;
          setDraftConflict({
            ownerId: userId,
            date: saveDate,
            draftRevision: durableRequest.draftRevision ?? null,
            localDraft: data,
            cloudEntry: latest,
            cloudData,
            localIsFullSnapshot: true,
          });
          setDraftConflictError(null);
          setSaved(false);
          setDirty(false);
          setSaveError(new CheckInError("conflict", "checkin.error_save_conflict", 409));
          return;
        }
        returnedEntry = latest;
      }

      const stagedPhotoCommitted =
        Boolean(photoIntent.photo_path) &&
        returnedEntry.photo_path === photoIntent.photo_path;
      await settlePhotoLease(stagedPhotoCommitted);

      let returnedBaseline: EntrySaveBaseline;
      try {
        returnedBaseline = baselineFromEntry(returnedEntry);
      } catch (error) {
        setSaveError(toCheckInError(error));
        return;
      }
      const savedData = fromEntry(
        returnedEntry as unknown as Record<string, unknown>
      );
      const formSnapshotMatches = pendingSaveMatchesForm(data, durableRequest);
      const draftResolution = await draftWriter.markSaved(
        saveDate,
        durableRequest.draftRevision,
        fromDraft(durableRequest.draftSnapshot)
      );
      const draftInspectionSucceeded = ["cleared", "missing", "preserved"].includes(
        draftResolution.status
      );
      const pendingRemoved = draftInspectionSucceeded && await clearPendingSave(
        resolveBrowserStorage(),
        saveDate,
        userId,
        durableRequest.requestId
      );
      if (pendingRemoved) {
        pendingSaveRef.current = null;
        setPendingSave(null);
        setPendingSaveNeedsReplay(false);
        setPendingSaveBlocked(false);
      } else {
        setDraftStorageError(true);
        setSaveError(new CheckInError("save_failed", "checkin.error_pending_cleanup", 507));
      }

      // The CAS write protects the entry row. Keep replaced/cleared objects for
      // now: legacy mobile writers may still reference an old URL after this
      // commit, and deleting it from the client would make that data unrecoverable.
      if (saveDate !== dateRef.current) return;

      setStoredEntry(returnedEntry);
      setSaveBaseline(returnedBaseline);
      baselineDataRef.current = savedData;
      saveIntentRef.current = null;
      setDraftConflict(null);
      setDraftConflictError(null);
      setReflectionError(null);
      setReflectionCached(false);
      notifyDataChanged("entries");

      if (draftResolution.status === "preserved") {
        // Another tab advanced the durable draft after this request was made.
        // Adopt that exact snapshot on screen without writing over its revision.
        const newerDraft = draftResolution.data;
        dataRef.current = newerDraft;
        setData(newerDraft);
        if (newerDraft.photoPath && photoLifecycle.track(userId, saveDate, newerDraft.photoPath)) {
          photoLifecycle.preserve(userId, saveDate, newerDraft.photoPath);
        }
        draftWriter.observeRevision(saveDate, draftResolution.revision);
        draftWriter.markEdited();
        setSaved(false);
        setDirty(true);
        setEditing(true);
        setDraftRestored(true);
        setDraftStorageSaved(true);
        setDraftStorageError(!pendingRemoved);
        setPhotoCleared(newerDraft.photoCleared);
        setDraftRecoveryNotice(false);
        setLateNightHint(false);
        setGoalsNonce((value) => value + 1);
        onSaveDone();
        return;
      }

      if (!formSnapshotMatches) {
        // The immutable older request was resolved, but the user made newer
        // in-tab edits. Keep the selected form and persist it against the now-
        // current revision only after the exact saved snapshot was inspected.
        setSaved(false);
        setDirty(true);
        setEditing(true);
        setDraftRestored(true);
        setDraftRecoveryNotice(false);
        setPhotoCleared(data.photoCleared);
        draftWriter.markEdited();
        if (draftInspectionSucceeded) await flushDraft(saveDate, data);
        else setDraftStorageError(true);
        return;
      }

      // The pending snapshot succeeded. A matching draft was cleared only when
      // its durable revision still matched the request's observed revision.
      dataRef.current = savedData;
      setData(savedData);
      setSaved(true);
      setDirty(false);
      setEditing(true);
      setPhotoCleared(false);
      setDraftRestored(false);
      setDraftRecoveryNotice(false);
      setLateNightHint(false);
      setGoalsNonce((value) => value + 1);
      setDraftStorageSaved(false);
      if (!pendingRemoved || !draftInspectionSucceeded) setDraftStorageError(true);
      else setDraftStorageError(false);
      onSaveDone();
      setPostSaveOpen(true);
      checkAndUnlockAchievements({
        mood: savedData.mood,
        hasPhoto: Boolean(savedData.photoPath),
        hasScale: Object.values(savedData.scaleValues || {}).some(
          (value) => value > 0
        ),
      })
        .then((unlocked) => {
          if (unlocked.length > 0) console.log("🏆 Unlocked:", unlocked);
        })
        .catch(() => {});
      // A day with no reflection yet gets one in the background; the route
      // caches, so an existing reflection is never regenerated here. This is
      // the only automatic request: the dialog only displays its state.
      if (!savedData.aiReflection) {
        void requestReflection(saveDate, { afterSave: true });
      }
    } finally {
      if (!photoLeaseSettled) void settlePhotoLease("unknown");
      setSaving(false);
    }
  };

  // Refresh the imperative replay target after each commit so it always sees
  // the current date, form snapshot and session closure.
  useEffect(() => {
    handleSaveRef.current = handleSave;
  });

  useEffect(() => {
    if (
      !pendingSaveNeedsReplay ||
      !pendingSave ||
      pendingSaveBlocked ||
      localOnly ||
      loading ||
      loadError ||
      loadedKey !== requestKey
    ) return;
    const replayKey = `${pendingSave.ownerId}:${pendingSave.date}:${pendingSave.requestId}`;
    if (autoReplayAttemptRef.current === replayKey) return;
    autoReplayAttemptRef.current = replayKey;
    void handleSaveRef.current(pendingSave);
  }, [
    pendingSave,
    pendingSaveNeedsReplay,
    pendingSaveBlocked,
    localOnly,
    loading,
    loadError,
    loadedKey,
    requestKey,
  ]);

  async function requestReflection(
    requestDate: string,
    options: { afterSave?: boolean } = {}
  ) {
    if (localOnly) return;
    // A reflection reads the saved row. While a save is in flight the row is
    // still the previous version, so generating then would persist prose about
    // stale data. The section button is also inert, but this guard covers the
    // programmatic path. `afterSave` is the one legitimate call made before the
    // `saving` state has re-rendered; the row is already written by then.
    if (!options.afterSave && (saving || saveInFlight.current)) return;
    if (photoUploading) return;
    if (reflectionInFlight.current) return;
    if (requestDate !== dateRef.current) return;

    reflectionInFlight.current = true;
    const token = ++reflectionToken.current;
    setReflectionLoading(true);
    setReflectionError(null);
    setReflectionCached(false);

    try {
      // Identifiers only: the route reads the saved row and its history from
      // Supabase. Form fields (mood, note, activities, ...) never leave here.
      const requestBody = buildReflectionRequest({
        userId,
        date: requestDate,
        lang,
        userName,
      });
      const resp = await fetch("/api/ai/reflect", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${getAccessToken()}`,
        },
        body: JSON.stringify(requestBody),
      });
      if (!resp.ok) throw aiFailedError(resp.status);

      const json = await resp.json().catch(() => null);
      const text =
        typeof json?.reflection === "string" ? json.reflection.trim() : "";
      if (!text) throw aiEmptyError();
      if (token !== reflectionToken.current) return;

      if (json?.cached === true) {
        // The endpoint returns only prose; refresh the exact row before adopting
        // it as our CAS baseline. A stale cached response is never enough to
        // overwrite the baseline or the user's current form.
        const latest = await getEntryOrThrow(requestDate);
        if (token !== reflectionToken.current) return;
        if (!latest || latest.user_id !== userId || latest.date !== requestDate) {
          throw aiPersistError(new Error("Cached reflection read returned an out-of-scope row"));
        }
        const reconciled = reconcileReflectionEntry({
          ownerId: userId,
          date: requestDate,
          currentData: dataRef.current,
          previousBaseline: baselineDataRef.current ?? dataRef.current,
          updatedEntry: latest as unknown as Record<string, unknown>,
        });
        dataRef.current = reconciled.data;
        setData(reconciled.data);
        setStoredEntry(latest);
        setSaveBaseline(reconciled.baseline);
        baselineDataRef.current = reconciled.serverData;
        setPhotoCleared(reconciled.data.photoCleared);
        if (latest.ai_reflection?.trim() !== text) {
          throw aiPersistError(new Error("Cached reflection changed before row confirmation"));
        }
        setReflectionCached(true);
        return;
      }

      const patch = reflectionPatch(requestDate, text);
      if (!patch) throw aiEmptyError();
      // The response is the authoritative row produced by the partial patch.
      // Rebase form edits made while AI was running and advance the CAS token.
      const updated = await patchEntry(patch);
      if (token !== reflectionToken.current) return;
      const reconciled = reconcileReflectionEntry({
        ownerId: userId,
        date: requestDate,
        currentData: dataRef.current,
        previousBaseline: baselineDataRef.current ?? dataRef.current,
        updatedEntry: updated as unknown as Record<string, unknown>,
      });
      dataRef.current = reconciled.data;
      setData(reconciled.data);
      setStoredEntry(updated);
      setSaveBaseline(reconciled.baseline);
      baselineDataRef.current = reconciled.serverData;
      setPhotoCleared(reconciled.data.photoCleared);
    } catch (error) {
      if (token !== reflectionToken.current) return;
      const mapped = isCheckInError(error)
        ? error.kind === "ai_failed" || error.kind === "signed_out"
          ? error
          : aiPersistError(error)
        : aiNetworkError(error);
      setReflectionError(mapped);
    } finally {
      if (token === reflectionToken.current) {
        setReflectionLoading(false);
        reflectionInFlight.current = false;
      }
    }
  }

  // ── Activity mutations ──
  const toggleActivity = (label: string) => {
    updateData((current) => ({
      ...current,
      activities: current.activities.includes(label)
        ? current.activities.filter((value) => value !== label)
        : [...current.activities, label],
    }));
  };

  const addActivity = async (input: {
    name: string;
    icon: string;
    category: string;
  }): Promise<string | null> => {
    if (localOnly) return "offline.checkin_notice";
    const key = definitionKey(input.name);
    if (!userId) return "checkin.error_save_auth";
    if (isDuplicateDefinition(activityDefs, input.name)) {
      return "activities.error_duplicate";
    }
    setActivityError(null);

    const created: ActivityDef = {
      key,
      label: input.name,
      icon: input.icon,
      category: input.category,
      color: "#6366f1",
      source: "custom",
    };

    const failure = await mutateCatalogue({
      request: () =>
        fetch("/api/manage-activities", {
          method: "POST",
          headers: authJsonHeaders(),
          body: JSON.stringify({
            action: "add",
            userId,
            key,
            label: input.name,
            icon: input.icon,
            category: input.category,
          }),
        }),
      apply: (body) => {
        const returnedKey =
          body &&
          typeof body === "object" &&
          typeof (body as { key?: unknown }).key === "string"
            ? (body as { key: string }).key
            : key;
        setActivityDefs((previous) =>
          previous.some((def) => def.key === returnedKey)
            ? previous
            : [...previous, { ...created, key: returnedKey }]
        );
        notifyDataChanged("activities");
      },
      errorCode: "activities.error_save",
      networkCode: "activities.error_network",
    });
    if (failure) setActivityError(failure);
    return failure;
  };

  const removeActivity = async (key: string): Promise<string | null> => {
    if (localOnly) return "offline.checkin_notice";
    const removed = activityDefs.find((def) => def.key === key);
    if (!userId) return "checkin.error_save_auth";
    setActivityError(null);

    const failure = await mutateCatalogue({
      request: () =>
        fetch("/api/manage-activities", {
          method: "POST",
          headers: authJsonHeaders(),
          body: JSON.stringify({ action: "remove", userId, key }),
        }),
      apply: () => {
        if (!removed) return;
        setActivityDefs((previous) => previous.filter((def) => def.key !== key));
        setHiddenActivities((previous) => [...previous, removed]);
        updateData((current) => ({
          ...current,
          activities: current.activities.filter(
            (label) => label !== removed.label
          ),
        }));
        notifyDataChanged("activities");
      },
      errorCode: "activities.error_remove",
      networkCode: "activities.error_remove_network",
    });
    if (failure) setActivityError(failure);
    return failure;
  };

  const restoreActivity = async (key: string): Promise<string | null> => {
    if (localOnly) return "offline.checkin_notice";
    const restored = hiddenActivities.find((def) => def.key === key);
    if (!userId) return "checkin.error_save_auth";
    setActivityError(null);

    const failure = await mutateCatalogue({
      request: () =>
        fetch("/api/manage-activities", {
          method: "POST",
          headers: authJsonHeaders(),
          body: JSON.stringify({ action: "restore", userId, key }),
        }),
      apply: () => {
        if (!restored) return;
        setActivityDefs((previous) =>
          previous.some((def) => def.key === key)
            ? previous
            : [...previous, restored]
        );
        setHiddenActivities((previous) =>
          previous.filter((def) => def.key !== key)
        );
        notifyDataChanged("activities");
      },
      errorCode: "activities.error_restore",
      networkCode: "activities.error_restore_network",
    });
    if (failure) setActivityError(failure);
    return failure;
  };

  // ── Habit mutations ──
  const toggleHabit = (key: string, checked: boolean) => {
    updateData((current) => ({
      ...current,
      habits: { ...current.habits, [key]: checked },
    }));
  };

  const addHabit = async (input: {
    name: string;
    icon: string;
    isNegative: boolean;
  }): Promise<string | null> => {
    if (localOnly) return "offline.checkin_notice";
    const key = definitionKey(input.name);
    if (!userId) return "checkin.error_save_auth";
    if (isDuplicateDefinition(habitDefs, input.name)) {
      return "habits.error_duplicate";
    }
    setHabitError(null);

    const habit: HabitDef = {
      key,
      label: input.name,
      icon: input.icon,
      category: "vlastní",
      color: "#6366f1",
      is_negative: input.isNegative,
      source: "custom",
    };

    const failure = await mutateCatalogue({
      request: () =>
        fetch("/api/manage-habits", {
          method: "POST",
          headers: authJsonHeaders(),
          body: JSON.stringify({
            action: "add",
            userId,
            key,
            label: input.name,
            icon: input.icon,
            is_negative: input.isNegative,
          }),
        }),
      apply: () => {
        // No `updateData` here: a new habit is a catalogue entry, not an
        // answered switch. Writing `{key: false}` would persist a false value
        // the user never touched.
        setHabitDefs((previous) =>
          previous.some((def) => def.key === key)
            ? previous
            : [...previous, habit]
        );
        notifyDataChanged("habits");
      },
      errorCode: "habits.error_save",
      networkCode: "habits.error_save",
    });
    if (failure) setHabitError(failure);
    return failure;
  };

  const updateHabit = async (
    key: string,
    input: { name: string; icon: string; isNegative: boolean }
  ): Promise<string | null> => {
    if (localOnly) return "offline.checkin_notice";
    if (!userId) return "checkin.error_save_auth";
    if (isDuplicateDefinition(habitDefs, input.name, key)) {
      return "habits.error_duplicate";
    }
    setHabitError(null);

    const failure = await mutateCatalogue({
      request: () =>
        fetch("/api/manage-habits", {
          method: "POST",
          headers: authJsonHeaders(),
          body: JSON.stringify({
            action: "update",
            userId,
            key,
            label: input.name,
            icon: input.icon,
            is_negative: input.isNegative,
          }),
        }),
      apply: () => {
        setHabitDefs((previous) =>
          previous.map((habit) =>
            habit.key === key
              ? {
                  ...habit,
                  label: input.name,
                  icon: input.icon,
                  is_negative: input.isNegative,
                }
              : habit
          )
        );
        notifyDataChanged("habits");
      },
      errorCode: "habits.error_save",
      networkCode: "habits.error_save",
    });
    if (failure) setHabitError(failure);
    return failure;
  };

  const removeHabit = async (key: string): Promise<string | null> => {
    if (localOnly) return "offline.checkin_notice";
    if (!userId) return "checkin.error_save_auth";
    setHabitError(null);

    const failure = await mutateCatalogue({
      request: () =>
        fetch("/api/manage-habits", {
          method: "POST",
          headers: authJsonHeaders(),
          body: JSON.stringify({ action: "remove", userId, key }),
        }),
      apply: () => {
        setHabitDefs((previous) =>
          previous.filter((habit) => habit.key !== key)
        );
        // The definition is gone; its answer must go with it.
        updateData((current) => {
          const habits = { ...current.habits };
          delete habits[key];
          return { ...current, habits };
        });
        notifyDataChanged("habits");
      },
      errorCode: "habits.error_save",
      networkCode: "habits.error_save",
    });
    if (failure) setHabitError(failure);
    return failure;
  };

  // ── Scales ──
  const changeScale = (scale: Scale, value: number) => {
    const clamped = Math.min(
      scale.max_value,
      Math.max(scale.min_value, Math.trunc(value))
    );
    updateData((current) => ({
      ...current,
      scaleValues: { ...current.scaleValues, [scale.id]: clamped },
    }));
  };

  const clearScale = (scale: Scale) => {
    // 0 (not a missing key) so `toPayload` can delete the mirrored row.
    updateData((current) => ({
      ...current,
      scaleValues: { ...current.scaleValues, [scale.id]: 0 },
    }));
  };

  // ── Render ──
  const stored =
    storedEntry && isRecordedMood(storedEntry.mood) ? storedEntry : null;
  // A reflection can only be generated for a day that is already stored and
  // has something to reflect on; an empty shell row is not a check-in. A stored
  // reflection is enough on its own (it can be re-read/regenerated).
  const reflectionCanGenerate =
    Boolean(storedEntry) && (hasContent(data) || Boolean(data.aiReflection));

  return (
    <div className="space-y-4 pb-6">
      {localOnly && !loading ? (
        <p
          role="status"
          aria-live="polite"
          className="rounded-xl border border-white/10 bg-white/[0.04] px-4 py-3 text-[13px] leading-5 text-white/75"
        >
          {localDraftFound ? t("offline.draft_restored") : t("offline.no_draft")}
        </p>
      ) : null}
      <CheckInHeader
        date={currentDate}
        today={today}
        lang={lang}
        disabled={saving || photoUploading || navigationPending}
        error={saveError?.code ?? null}
        errorRef={saveErrorRef}
        draftRestored={draftRestored}
        lateNightHint={lateNightHint}
        onShift={shiftDay}
        onPickDate={pickDate}
      />

      {loading ? (
        <div
          role="status"
          aria-live="polite"
          className="flex min-h-[40vh] items-center justify-center text-[13px] text-[color:var(--text-2)]"
        >
          {t("common.loading")}
        </div>
      ) : loadError ? (
        <div
          role="alert"
          className="glass-card px-5 py-6 text-center"
        >
          <p className="text-sm text-red-200">
            {draftLockUnsupported && loadError.code === "checkin.error_draft_storage"
              ? t("checkin.draft_unsupported_browser")
              : t(loadError.code)}
          </p>
          <button
            type="button"
            onClick={() => setReloadNonce((value) => value + 1)}
            className="mt-4 min-h-11 rounded-xl bg-indigo-500 px-5 text-sm font-medium text-white transition-colors hover:bg-indigo-400 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-300"
          >
            {t("checkin.error_retry")}
          </button>
        </div>
      ) : draftConflict ? (
        <DraftConflictPanel
          conflict={draftConflict}
          error={draftConflictError ? t(draftConflictError) : null}
          onResolve={handleDraftConflictResolution}
        />
      ) : draftCandidateConflict ? (
        <DraftCandidateConflictPanel
          snapshot={draftCandidateConflict}
          error={draftCandidateConflictError ? t(draftCandidateConflictError) : null}
          onResolve={handleDraftCandidateResolution}
        />
      ) : stored && !editing ? (
        <StoredDayView
          date={currentDate}
          entry={stored}
          habits={habitDefs}
          scales={scales}
          scalesError={scalesError}
          onRetry={() => setScalesNonce((value) => value + 1)}
          onEdit={() => setEditing(true)}
        />
      ) : (
        <CheckInForm
          data={data}
          date={currentDate}
          setData={updateData}
          onPhotoSelected={onPhotoSelected}
          onPhotoUploading={updatePhotoUploading}
          activities={activityDefs}
          hiddenActivities={hiddenActivities}
          activityError={activityError}
          onToggleActivity={toggleActivity}
          onAddActivity={addActivity}
          onRemoveActivity={removeActivity}
          onRestoreActivity={restoreActivity}
          habits={habitDefs}
          habitError={habitError}
          onToggleHabit={toggleHabit}
          onAddHabit={addHabit}
          onUpdateHabit={updateHabit}
          onRemoveHabit={removeHabit}
          scales={scales}
          scalesLoading={scalesLoading}
          scalesError={scalesError}
          onScaleChange={changeScale}
          onClearScale={clearScale}
          onRetryScales={() => setScalesNonce((value) => value + 1)}
          saving={saving}
          navigationPending={navigationPending}
          photoUploading={photoUploading}
          photoCleared={photoCleared}
          hasStoredPhoto={Boolean(storedEntry?.photo_path)}
          saved={saved}
          draftStorageError={draftStorageError}
          draftLockUnsupported={draftLockUnsupported}
          draftStorageSaved={draftStorageSaved}
          unsavedDraftCopy={unsavedDraftCopy}
          draftCopySucceeded={draftCopySucceeded}
          draftCopyFailed={draftCopyFailed}
          onCopyUnsavedDraft={handleCopyUnsavedDraft}
          onRetryDraftStorage={handleRetryDraftStorage}
          loadFailed={Boolean(loadError)}
          dirty={dirty}
          localOnly={localOnly}
          replacingCloudEntry={draftRecoveryNotice}
          goals={goalRows}
          goalsLoading={goalsLoading}
          goalsError={goalsError}
          onRetryGoals={() => setGoalsNonce((value) => value + 1)}
          onOpenGoals={onOpenGoals}
          reflectionLoading={reflectionLoading}
          reflectionError={reflectionError}
          reflectionCached={reflectionCached}
          reflectionCanGenerate={reflectionCanGenerate}
          onGenerateReflection={() => {
            if (reflectionCanGenerate && !dirty && !saving) {
              void requestReflection(currentDate);
            }
          }}
          onSave={handleSave}
          onViewPhoto={setViewingPhoto}
        />
      )}

      {viewingPhoto ? (
        <PhotoViewer
          src={viewingPhoto}
          alt={t("checkin.photo_alt", { date: currentDate })}
          onClose={() => setViewingPhoto(null)}
        />
      ) : null}

      {postSaveOpen ? (
        <PostSaveReflectionDialog
          reflection={data.aiReflection}
          loading={reflectionLoading}
          error={reflectionError}
          cached={reflectionCached}
          canGenerate={reflectionCanGenerate}
          onGenerate={() => {
            if (!saving) void requestReflection(currentDate);
          }}
          onClose={() => {
            setPostSaveOpen(false);
            onOpenOverview();
          }}
        />
      ) : null}
    </div>
  );
}
