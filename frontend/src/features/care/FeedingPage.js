// src/features/care/FeedingPage.js
// Flow 4 - rations page for the Groom (read-only) and the Vet (can approve).
// The Head Trainer edits rations on the Stable care page instead.
import { getUser } from "../auth/authService";
import HorseShell from "../horse/HorseShell";
import FeedingBoard from "./FeedingBoard";

export default function FeedingPage() {
  const editable = getUser()?.role === "VETERINARIAN";
  return (
    <HorseShell
      title={editable ? "Rations" : "Feeding"}
      subtitle={editable ? "Approve what each horse eats, meal by meal" : "What each horse eats, meal by meal"}
    >
      <FeedingBoard editable={editable} />
    </HorseShell>
  );
}
