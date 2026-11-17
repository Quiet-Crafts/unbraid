// Writes app/src/main/resources/shared_suffixes.txt from a copy of the Public Suffix List:
//   node tools/shared-suffixes.mjs public_suffix_list.dat > app/src/main/resources/shared_suffixes.txt
// Every rule of two labels or more from the ICANN section (endings registries hand out), with its "*." or "!" sign, in
// ASCII form, under the list's own notice (MPL-2.0). One-label rules are left out: a website needs two labels anyway. The
// private section (platforms such as github.io) is left out: a rule for a platform is the user's choice. The last line
// counts the rules, so the app can tell a file cut short.
import fs from "node:fs";
import url from "node:url";

const text = fs.readFileSync(process.argv[2], "utf8");
const lines = text.split("\n");
const field = (k) => text.match(new RegExp(`// ${k}: (\\S+)`))[1];
const icann = text.split("// ===BEGIN ICANN DOMAINS===")[1].split("// ===END ICANN DOMAINS===")[0].split("\n");
const rules = [...new Set(icann.map((l) => l.trim()).filter((l) => l && !l.startsWith("//") && l.includes("."))
  .map((r) => { const [, sign, name] = r.match(/^(\*\.|!)?(.*)$/); return (sign ?? "") + url.domainToASCII(name); }))].sort();
console.log([
  ...lines.slice(0, 3),
  "//",
  "// From the Public Suffix List, https://publicsuffix.org/list/public_suffix_list.dat",
  `// VERSION: ${field("VERSION")}, COMMIT: ${field("COMMIT")}`,
  "// Every rule of two labels or more from its ICANN section, in ASCII form, one per line (made by tools/shared-suffixes.mjs).",
  ...rules,
  `// RULES: ${rules.length}`,
].join("\n"));
