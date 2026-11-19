// PreToolUse(Bash): blocca comandi pericolosi o contrari al flusso SDLC.
// Exit 2 + stderr = comando bloccato, il messaggio torna a Claude.
import { readFileSync } from "node:fs";

let input = {};
try { input = JSON.parse(readFileSync(0, "utf8") || "{}"); } catch { process.exit(0); }
const cmd = String(input?.tool_input?.command ?? "");

const rules = [
  [/\bgit\s+push\b[^\n;&|]*(\s--force(?!-with-lease)\b|\s-f\b)/, "push forzato vietato: usa un nuovo commit o --force-with-lease solo su richiesta esplicita dell'utente"],
  [/\bgit\s+push\b[^\n;&|]*(\s|:)main(\s|$|[;&|])/,"push diretto su main vietato: apri una PR con /sdlc-pr"],
  [/--no-verify\b/, "--no-verify vietato: correggi il problema segnalato dagli hook"],
  [/\bgit\s+reset\s+--hard\b/, "git reset --hard richiede conferma esplicita dell'utente: chiedi prima"],
  [/\bgit\s+clean\s+-[a-z]*f/, "git clean -f richiede conferma esplicita dell'utente: chiedi prima"],
  [/\brm\s+-[a-z]*r[a-z]*f?[a-z]*\s+(\/|~|\$HOME|[A-Za-z]:[\\/]?)(\s|$)/i, "rm -r su root/home/drive vietato"],
  [/\badb\b[^\n;&|]*\bshell\s+(rm|su|setprop|settings\s+put)\b/, "comando adb distruttivo/di sistema: chiedi all'utente"],
  [/\bgh\s+repo\s+(delete|edit)\b/, "modifiche al repository GitHub vietate dagli agenti"],
];

for (const [re, msg] of rules) {
  if (re.test(cmd)) {
    process.stderr.write(`Bloccato da guard-bash: ${msg}\nComando: ${cmd}\n`);
    process.exit(2);
  }
}
process.exit(0);
