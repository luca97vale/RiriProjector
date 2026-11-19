// PreToolUse(Edit|Write|MultiEdit): impedisce modifiche a segreti e file generati/binari.
import { readFileSync } from "node:fs";

let input = {};
try { input = JSON.parse(readFileSync(0, "utf8") || "{}"); } catch { process.exit(0); }
const file = String(input?.tool_input?.file_path ?? "").replace(/\\/g, "/");
if (!file) process.exit(0);

const protectedPatterns = [
  [/\.(jks|keystore|p12)$/i, "keystore"],
  [/(^|\/)keystore\.properties$/, "credenziali di firma"],
  [/(^|\/)local\.properties$/, "file locale della macchina (sdk.dir)"],
  [/(^|\/)\.env(\..*)?$/, "variabili d'ambiente/segreti"],
  [/(^|\/)gradle\/wrapper\/gradle-wrapper\.jar$/, "wrapper binario: aggiornalo con ./gradlew wrapper"],
  [/(^|\/)(build|\.gradle)\//, "output generato"],
];

for (const [re, why] of protectedPatterns) {
  if (re.test(file)) {
    process.stderr.write(`Bloccato da protect-files: ${file} è protetto (${why}). Chiedi all'utente se serve davvero.\n`);
    process.exit(2);
  }
}
process.exit(0);
