import { createClient } from "@supabase/supabase-js";
import { cloudFetch } from "../cloud-access.ts";

const supabaseUrl = process.env.NEXT_PUBLIC_SUPABASE_URL!;
const supabaseKey = process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY!;

export function createSupabaseClient(accessToken?: string) {
  return createClient(supabaseUrl, supabaseKey, {
    auth: {
      persistSession: true,
      autoRefreshToken: true,
      detectSessionInUrl: false,
    },
    // A caller that already has the user's JWT can pass it here. The header is
    // used for both REST and Storage without mutating the persisted Supabase
    // session (setSession with an empty refresh token could log the user out).
    global: {
      fetch: cloudFetch,
      ...(accessToken
        ? { headers: { Authorization: `Bearer ${accessToken}` } }
        : {}),
    },
  });
}
