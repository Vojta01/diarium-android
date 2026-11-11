import { createSupabaseClient } from "./supabase/client";
import { readStoredSession } from "./auth-storage";
import {
  DASHBOARD_ERROR_CODES,
  dashboardReadError,
  signedOutError,
} from "./dashboard-errors";

export interface Template {
  id: string;
  user_id: string;
  name: string;
  content: string;
  sort_order: number;
  created_at: string;
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

export async function getTemplates(): Promise<Template[]> {
  const userId = await getCurrentUserId();
  if (!userId) return [];
  
  const sb = await getAuthenticatedClient();
  const { data, error } = await sb
    .from("templates")
    .select("*")
    .eq("user_id", userId)
    .order("sort_order", { ascending: true });
  
  if (error) {
    console.error("getTemplates error:", error);
    return [];
  }
  return data || [];
}

/**
 * Read-only list for the Templates destination.
 *
 * The legacy `getTemplates` above intentionally keeps its old
 * "empty on failure" contract for existing callers. A screen cannot tell that
 * apart from a real empty account, so this strict variant throws a semantic
 * dashboard error instead. It performs the same query and adds no write path.
 */
export async function getTemplatesOrThrow(): Promise<Template[]> {
  const userId = await getCurrentUserId();
  if (!userId) throw signedOutError();

  const sb = await getAuthenticatedClient();
  const { data, error, status } = await sb
    .from("templates")
    .select("*")
    .eq("user_id", userId)
    .order("sort_order", { ascending: true });

  if (error) {
    throw dashboardReadError(error, DASHBOARD_ERROR_CODES.templates, status);
  }
  return data || [];
}

export async function createTemplate(template: Omit<Template, 'id' | 'user_id' | 'created_at'>): Promise<Template> {
  const userId = await getCurrentUserId();
  if (!userId) throw new Error("Not authenticated");
  
  const sb = await getAuthenticatedClient();
  const { data, error } = await sb
    .from("templates")
    .insert({ ...template, user_id: userId })
    .select()
    .single();
  
  if (error) throw error;
  return data;
}

export async function updateTemplate(id: string, updates: Partial<Template>): Promise<void> {
  const sb = await getAuthenticatedClient();
  const { error } = await sb
    .from("templates")
    .update(updates)
    .eq("id", id);
  
  if (error) throw error;
}

export async function deleteTemplate(id: string): Promise<void> {
  const sb = await getAuthenticatedClient();
  const { error } = await sb
    .from("templates")
    .delete()
    .eq("id", id);
  
  if (error) throw error;
}

// Default templates for new users
export const DEFAULT_TEMPLATES: Omit<Template, 'id' | 'user_id' | 'created_at'>[] = [
  {
    name: "🌅 Ranní reflexe",
    content: "Dnes ráno se cítím...\n\n3 věci, na které se těším:\n1. \n2. \n3. \n\nCo dnes chci dokázat:",
    sort_order: 1,
  },
  {
    name: "🌙 Večerní shrnutí",
    content: "Dnešek byl...\n\nNejlepší moment dne:\n\nCo bych zlepšil/a:\n\nZa co jsem vděčný/á:",
    sort_order: 2,
  },
  {
    name: "💪 Těžký den",
    content: "Dnes to bylo těžké, protože...\n\nCo mě drží při životě:\n\nZítra bude líp, protože:",
    sort_order: 3,
  },
];
