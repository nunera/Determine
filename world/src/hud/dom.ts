/** Tiny typed DOM builders for the HUD (no framework: the HUD is a few hundred static nodes updated in place). */

type AttrValue = string | number | boolean | undefined | null;
export type Attrs = Record<string, AttrValue>;
export type Child = Node | string | null | undefined | false;

function applyAttrs(node: Element, attrs: Attrs): void {
  for (const [key, value] of Object.entries(attrs)) {
    if (value === undefined || value === null || value === false) continue;
    node.setAttribute(key, value === true ? "" : String(value));
  }
}

function append(node: Element, children: Child[]): void {
  for (const c of children) {
    if (c === null || c === undefined || c === false) continue;
    node.append(c);
  }
}

export function el<K extends keyof HTMLElementTagNameMap>(tag: K, attrs: Attrs = {}, ...children: Child[]): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  applyAttrs(node, attrs);
  append(node, children);
  return node;
}

const SVG_NS = "http://www.w3.org/2000/svg";

export function svg<K extends keyof SVGElementTagNameMap>(tag: K, attrs: Attrs = {}, ...children: Child[]): SVGElementTagNameMap[K] {
  const node = document.createElementNS(SVG_NS, tag);
  applyAttrs(node, attrs);
  append(node, children);
  return node;
}

/** Sets text only when it changed (cheap to call every frame). */
export function setText(node: Node, text: string): void {
  if (node.textContent !== text) node.textContent = text;
}
