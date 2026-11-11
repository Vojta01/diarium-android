import { redirect } from "next/navigation";

/**
 * Legacy `/stats` URL. The five-destination shell owns statistics now, so the
 * deep link lands on the shell with the Stats tab selected.
 */
export default function StatsPage() {
  redirect("/?view=stats");
}
