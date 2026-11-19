// PreToolUse(Edit|Write|MultiEdit): prevents edits to secrets and generated/binary files.
import { readFileSync } from "node:fs";

let input = {};
try { input = JSON.parse(readFileSync(0, "utf8") || "{}"); } catch { process.exit(0); }
const file = String(input?.tool_input?.file_path ?? "").replace(/\\/g, "/");
if (!file) process.exit(0);

const protectedPatterns = [
  [/\.(jks|keystore|p12)$/i, "keystore"],
  [/(^|\/)keystore\.properties$/, "signing credentials"],
  [/(^|\/)local\.properties$/, "machine-local file (sdk.dir)"],
  [/(^|\/)\.env(\..*)?$/, "environment variables/secrets"],
  [/(^|\/)gradle\/wrapper\/gradle-wrapper\.jar$/, "binary wrapper: update it with ./gradlew wrapper"],
  [/(^|\/)(build|\.gradle)\//, "generated output"],
];

for (const [re, why] of protectedPatterns) {
  if (re.test(file)) {
    process.stderr.write(`Blocked by protect-files: ${file} is protected (${why}). Ask the user if it is really needed.\n`);
    process.exit(2);
  }
}
process.exit(0);
