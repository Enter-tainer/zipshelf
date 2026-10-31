#!/usr/bin/env node
/**
 * 用 KaTeX 在构建期把 TeX 渲染成 HTML，直接粘进页面。
 *
 *   npm i katex
 *   node render_math.mjs 'a^2+b^2=c^2'                    # 行内
 *   node render_math.mjs --display '\int_0^1 f(x)\,dx'    # 行间
 *   printf 'x_i\n\\alpha^2\n' | node render_math.mjs      # 批量（每行一个）
 *
 * 输出：一行一段可以直接放进 <span class="mi"> 或 <span class="md"> 的 HTML。
 */
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
let katex;
try {
  katex = require("katex");
} catch {
  console.error("缺少 katex：先运行 `npm i katex`（或用 pnpm add katex@0.16.11）");
  process.exit(2);
}

const args = process.argv.slice(2);
const display = args.includes("--display");
const tex = args.filter((a) => a !== "--display").join(" ");

function render(src) {
  return katex.renderToString(src, {
    displayMode: display,
    throwOnError: false,
    strict: "ignore",
    trust: false,
    output: "htmlAndMathml", // 保留 MathML 副本（屏幕阅读器用，别裁）
  });
}

if (tex) {
  process.stdout.write(render(tex) + "\n");
} else {
  const chunks = [];
  process.stdin.on("data", (c) => chunks.push(c));
  process.stdin.on("end", () => {
    const text = Buffer.concat(chunks).toString("utf8");
    for (const line of text.split("\n")) {
      const t = line.trim();
      if (!t) continue;
      const html = render(t);
      process.stdout.write(
        display ? `<span class="md">${html}</span>\n` : `<span class="mi">${html}</span>\n`
      );
    }
  });
}
