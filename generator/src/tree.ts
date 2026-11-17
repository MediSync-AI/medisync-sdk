// The namespace tree of the client: `connect.transfers.create` is the method `create` of the
// node `connect.transfers`. Both emitters walk it.
import type { Helper } from './names.ts';
import type { Ir, Operation } from './ir.ts';
import { pascal } from './ir.ts';

export interface Node {
  /** Dotted path, `connect.transfers`. Empty for the root. */
  path: string;
  /** Class name: `ConnectTransfers`. */
  className: string;
  children: Map<string, Node>;
  methods: { name: string; op?: Operation; helper?: Helper }[];
}

export function buildTree(ir: Ir): Node {
  const root: Node = { path: '', className: 'Root', children: new Map(), methods: [] };
  const place = (dotted: string) => {
    const parts = dotted.split('.');
    const method = parts.pop() as string;
    let node = root;
    for (const part of parts) {
      let child = node.children.get(part);
      if (!child) {
        const path = node.path ? `${node.path}.${part}` : part;
        child = { path, className: path.split('.').map(pascal).join(''), children: new Map(), methods: [] };
        node.children.set(part, child);
      }
      node = child;
    }
    if (node.methods.some((m) => m.name === method) || node.children.has(method)) throw new Error(`Two members named ${dotted}`);
    return { node, method };
  };
  for (const op of ir.operations) {
    const { node, method } = place(op.name);
    node.methods.push({ name: method, op });
  }
  for (const helper of ir.helpers) {
    const { node, method } = place(helper.name);
    node.methods.push({ name: method, helper });
  }
  return root;
}

export function walk(node: Node, visit: (n: Node) => void): void {
  for (const child of node.children.values()) walk(child, visit);
  visit(node);
}
