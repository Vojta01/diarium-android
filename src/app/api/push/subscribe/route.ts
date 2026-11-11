import { createClient } from "@supabase/supabase-js";
import { verifyAuth } from "@/lib/auth";
import { createPushSubscriptionHandlers } from "@/lib/push-subscription-handler";
import { registerFcmToken, type FcmTokenStore } from "@/lib/fcm-token-store";
import { getRedis } from "@/lib/redis";

const SUPABASE_URL = process.env.NEXT_PUBLIC_SUPABASE_URL!;
const SERVICE_KEY = process.env.SUPABASE_SERVICE_ROLE_KEY || "";

async function withFcmStore<T>(run: (store: FcmTokenStore) => Promise<T>): Promise<T> {
  const supabase = createClient(SUPABASE_URL, SERVICE_KEY);
  const store: FcmTokenStore = {
    insert: async (record) => {
      const { error } = await supabase.from("push_tokens").insert(record);
      return { code: error?.code ?? null };
    },
    getOwner: async (token) => {
      const { data, error } = await supabase
        .from("push_tokens")
        .select("user_id")
        .eq("token", token)
        .maybeSingle();
      if (error) throw error;
      return data?.user_id ?? null;
    },
    deleteOwned: async (userId, token) => {
      const { error } = await supabase
        .from("push_tokens")
        .delete()
        .eq("user_id", userId)
        .eq("token", token);
      if (error) throw error;
    },
  };
  return run(store);
}

const handlers = createPushSubscriptionHandlers({
  authenticate: verifyAuth,
  getRedis,
  registerFcmToken: (userId, token) =>
    withFcmStore((store) => registerFcmToken(store, userId, token)),
  deleteFcmToken: (userId, token) =>
    withFcmStore((store) => store.deleteOwned(userId, token)),
});

export const POST = handlers.POST;
export const DELETE = handlers.DELETE;
