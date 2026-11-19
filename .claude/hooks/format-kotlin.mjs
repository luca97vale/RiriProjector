// PostToolUse(Edit|Write|MultiEdit): formatta con Spotless (ktlint) il solo file Kotlin modificato.
// Silenzioso se Gradle/Spotless non sono ancora configurati. Non blocca mai (exit 0),
// ma se ktlint segnala errori non correggibili li restituisce a Claude come feedback.
import { readFileSync, existsSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { join, resolve } from "node:path";

let input = {};
try { input = JSON.parse(readFileSync(0, "utf8") || "{}"); } catch { process.exit(0); }
const file = String(input?.tool_input?.file_path ?? "");
if (!/\.kts?$/.test(file)) process.exit(0);

const root = process.env.CLAUDE_PROJECT_DIR || process.cwd();
const isWin = process.platform === "win32";
const gradlew = join(root, isWin ? "gradlew.bat" : "gradlew");
if (!existsSync(gradlew)) process.exit(0);
if (!/spotless/.test(safeRead(join(root, "build.gradle.kts")))) process.exit(0);

const res = spawnSync(
  gradlew,
  ["spotlessApply", `-PspotlessIdeHook=${resolve(file)}`, "--quiet"],
  { cwd: root, encoding: "utf8", shell: isWin, timeout: 110_000 },
);
if (res.status !== 0) {
  const out = `${res.stdout ?? ""}${res.stderr ?? ""}`.trim().split("\n").slice(-20).join("\n");
  console.log(JSON.stringify({
    hookSpecificOutput: {
      hookEventName: "PostToolUse",
      additionalContext: `spotless/ktlint su ${file} ha segnalato problemi:\n${out}`,
    },
  }));
}
process.exit(0);

function safeRead(p) { try { return readFileSync(p, "utf8"); } catch { return ""; } }
