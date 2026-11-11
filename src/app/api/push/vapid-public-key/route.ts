import { VAPID_PUBLIC_KEY } from "@/lib/vapid";

export const runtime = "edge";

export async function GET() {
  return Response.json({ publicKey: VAPID_PUBLIC_KEY });
}
