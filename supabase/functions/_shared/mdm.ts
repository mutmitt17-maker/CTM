// Commands to the device-management side.
// Default mode is "dry-run": the command is acknowledged WITHOUT touching any real phone.
// Set MDM_MODE=live only after the real adapters below are written and tested on a spare phone.

export type DeviceCommand = {
  id: string;
  managed_device_id: string;
  command_type: "apply_policy" | "lock" | "unlock" | "show_warning" | "release_device" | "request_attestation";
  payload: Record<string, any>;
  attempts: number;
};

export async function sendCommand(cmd: DeviceCommand): Promise<string> {
  const mode = Deno.env.get("MDM_MODE") ?? "dry-run";
  if (mode !== "live") {
    console.log("[dry-run] device command", cmd.command_type, cmd.managed_device_id, JSON.stringify(cmd.payload));
    return "dry-run";
  }

  switch (cmd.payload.provider) {
    case "knox_guard":
      // TODO: Samsung Knox Guard API (lock / unlock / release).
      throw new Error("Knox Guard adapter not implemented yet");
    case "apple_abm":
      // TODO: Apple Business Manager / MDM (lost mode, restrictions, release from supervision).
      throw new Error("Apple ABM adapter not implemented yet");
    case "android_enterprise":
      // TODO: Android Management API / your MDM (lock task, policy, remove device owner).
      throw new Error("Android Enterprise adapter not implemented yet");
    default:
      throw new Error(`unknown provider: ${cmd.payload.provider}`);
  }
}
