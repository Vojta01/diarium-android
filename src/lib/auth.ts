import { createClient } from '@supabase/supabase-js';
import { NextRequest } from 'next/server';

const SUPABASE_URL = process.env.NEXT_PUBLIC_SUPABASE_URL!;
const SERVICE_KEY = process.env.SUPABASE_SERVICE_ROLE_KEY || '';

/**
 * Verify authentication by extracting Bearer token from the Authorization header
 * and validating it against Supabase's auth.getUser().
 *
 * @param request - The incoming NextRequest or standard Request
 * @returns The authenticated user object, or null if auth fails
 */
export async function verifyAuth(request: NextRequest | Request) {
  const authHeader = request.headers.get('Authorization');
  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    return null;
  }

  const token = authHeader.slice(7).trim();
  if (!token) return null;

  try {
    const supabase = createClient(SUPABASE_URL, SERVICE_KEY);
    const { data, error } = await supabase.auth.getUser(token);
    if (error || !data.user) {
      console.error('Auth verification failed:', error?.message);
      return null;
    }
    return data.user;
  } catch (e) {
    console.error('Auth verification exception:', e);
    return null;
  }
}
