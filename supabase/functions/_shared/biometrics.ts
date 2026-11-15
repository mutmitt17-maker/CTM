// Face check: liveness of the selfie and match between the selfie and the ID photo.
// Default mode is "dry-run": nothing is decided, the work stays waiting (applications stay "pending").
// BIOMETRICS_MODE=simulate_pass : every check passes. ONLY for a test project, never in production:
//   it would approve everyone's face. The stored check carries provider "simulated" so it can be found.
// BIOMETRICS_MODE=live : calls the real provider chosen by BIOMETRICS_PROVIDER (adapter to be written).

export type FaceCheck = {
  provider: string;
  liveness_passed: boolean;
  face_match_score: number; // 0 to 1
};

/** Returns null when no decision was made (dry-run): the caller must record nothing. */
export async function checkFaces(selfie: Uint8Array, document: Uint8Array): Promise<FaceCheck | null> {
  const mode = Deno.env.get("BIOMETRICS_MODE") ?? "dry-run";

  if (mode === "dry-run") {
    console.log("[dry-run] face check skipped", selfie.length, "bytes selfie,", document.length, "bytes document");
    return null;
  }

  if (mode === "simulate_pass") {
    console.warn("[simulate_pass] face check SIMULATED: do not use in production");
    return { provider: "simulated", liveness_passed: true, face_match_score: 0.99 };
  }

  if (mode !== "live") throw new Error(`unknown BIOMETRICS_MODE: ${mode}`);

  const provider = Deno.env.get("BIOMETRICS_PROVIDER") ?? "";
  switch (provider) {
    // TODO: add the adapter of the chosen provider here (it must return the liveness result and a
    // match score between 0 and 1 computed by the provider, never by the phone).
    default:
      throw new Error(`face provider adapter not implemented: "${provider}"`);
  }
}
