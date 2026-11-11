import { createSupabaseClient } from "./supabase/client.ts";
import { readStoredSession } from "./auth-storage.ts";
import {
  DASHBOARD_ERROR_CODES,
  dashboardReadError,
  signedOutError,
} from "./dashboard-errors.ts";

export interface Goal {
  id: string;
  user_id: string;
  activity_key: string;
  name: string;
  target_count: number;
  frequency: 'daily' | 'weekly' | 'monthly';
  is_active: boolean;
  created_at: string;
  archived_at?: string;
  completed_at?: string;
}

function getSupabase() {
  return createSupabaseClient();
}

function getAccessToken(): string | null {
  const session = readStoredSession();
  return session?.access_token || null;
}

async function getAuthenticatedClient() {
  const token = getAccessToken();
  const sb = getSupabase();
  if (token) {
    await sb.auth.setSession({ access_token: token, refresh_token: '' }).catch(() => {});
  }
  return sb;
}

async function getCurrentUserId(): Promise<string | null> {
  const session = readStoredSession();
  if (session?.user?.id) return session.user.id;
  
  const token = getAccessToken();
  if (token) {
    try {
      const payload = JSON.parse(atob(token.split('.')[1]));
      return payload.sub;
    } catch {}
  }
  
  const sb = getSupabase();
  const { data } = await sb.auth.getUser();
  return data.user?.id || null;
}

export async function getGoals(): Promise<Goal[]> {
  const userId = await getCurrentUserId();
  if (!userId) throw signedOutError();

  const sb = await getAuthenticatedClient();
  const { data, error, status } = await sb
    .from("goals")
    .select("*")
    .eq("user_id", userId)
    .eq("is_active", true)
    .order("created_at", { ascending: false });

  // A failed read must never look like "this account has no goals": the
  // dashboard renders an error + retry state for this, while only a successful
  // empty list may show the empty-goals CTA.
  if (error) {
    throw dashboardReadError(error, DASHBOARD_ERROR_CODES.goals, status);
  }
  return data || [];
}

export async function createGoal(goal: Omit<Goal, 'id' | 'user_id' | 'created_at'>): Promise<Goal> {
  const userId = await getCurrentUserId();
  if (!userId) throw new Error("Not authenticated");
  
  const sb = await getAuthenticatedClient();
  const { data, error } = await sb
    .from("goals")
    .insert({ ...goal, user_id: userId })
    .select()
    .single();
  
  if (error) throw error;
  return data;
}

export async function updateGoal(id: string, updates: Partial<Goal>): Promise<void> {
  const sb = await getAuthenticatedClient();
  const { error } = await sb
    .from("goals")
    .update(updates)
    .eq("id", id);
  
  if (error) throw error;
}

export async function deleteGoal(id: string): Promise<void> {
  const sb = await getAuthenticatedClient();
  const { error } = await sb
    .from("goals")
    .delete()
    .eq("id", id);
  
  if (error) throw error;
}
