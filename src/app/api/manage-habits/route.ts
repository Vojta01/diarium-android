import { NextResponse } from "next/server";
import { createClient } from "@supabase/supabase-js";
import { verifyAuth } from "@/lib/auth";

export async function POST(req: Request) {
  const supabaseUrl = process.env.NEXT_PUBLIC_SUPABASE_URL;
  const serviceKey = process.env.SUPABASE_SERVICE_ROLE_KEY;

  if (!supabaseUrl || !serviceKey) {
    return NextResponse.json({ error: "Missing env vars" }, { status: 500 });
  }

  const user = await verifyAuth(req);
  if (!user) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  const sb = createClient(supabaseUrl, serviceKey);
  const { action, userId, key, label, icon, is_negative } = await req.json();

  if (!userId || !action || !key) {
    return NextResponse.json({ error: "Missing required fields" }, { status: 400 });
  }

  // Verify the caller owns this userId
  if (userId !== user.id) {
    return NextResponse.json({ error: "Forbidden: user_id mismatch" }, { status: 403 });
  }

  if (action === "add") {
    const { error } = await sb.from("user_habits").upsert(
      {
        user_id: userId,
        key,
        label: label || key,
        icon: icon || "✅",
        is_negative: is_negative ?? false,
        is_active: true,
      },
      { onConflict: "user_id,key" }
    );

    if (error) {
      return NextResponse.json({ error: error.message }, { status: 500 });
    }
    return NextResponse.json({ ok: true, action: "add", key });
  }

  if (action === "update") {
    // Rename / re-icon an existing habit. For a *default* habit this row becomes an
    // override that getHabits() layers on top of the built-in catalog entry.
    const { error } = await sb.from("user_habits").upsert(
      {
        user_id: userId,
        key,
        label: label || key,
        icon: icon || "✅",
        is_negative: is_negative ?? false,
        is_active: true,
      },
      { onConflict: "user_id,key" }
    );

    if (error) {
      return NextResponse.json({ error: error.message }, { status: 500 });
    }
    return NextResponse.json({ ok: true, action: "update", key });
  }

  if (action === "remove") {
    // Mark as inactive (soft delete)
    const { error } = await sb.from("user_habits").upsert(
      {
        user_id: userId,
        key,
        label: key,
        icon: "✅",
        is_active: false,
      },
      { onConflict: "user_id,key" }
    );

    if (error) {
      return NextResponse.json({ error: error.message }, { status: 500 });
    }
    return NextResponse.json({ ok: true, action: "remove", key });
  }

  return NextResponse.json({ error: "Unknown action" }, { status: 400 });
}
