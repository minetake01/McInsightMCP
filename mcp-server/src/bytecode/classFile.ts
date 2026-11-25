/**
 * Structural inspection of a class file: which class it is, what strings it holds, what it
 * references, and which static fields it binds from string literals.
 *
 * <p>The last part is what makes ID tracing possible: a registry field is bound as
 * `FIELD = "registry_id"` (a `ConstantValue` attribute, or `ldc` + `putstatic` in `<clinit>`), and
 * finding that binding is how a resource id is tied to the Java field that registers it.
 */

import { parseConstantPool } from './constantPool.js';
import { getJvmOpcodeLength } from './opcodes.js';

/** A window in which a `ldc` string and a `putstatic` are considered related. */
const STATIC_BINDING_WINDOW_BYTES = 512;

export interface ParsedClassInfo {
  thisClassInternal: string;
  thisClassDot: string;
  stringLiterals: string[];
  fieldRefs: Array<{ owner: string; name: string; descriptor: string }>;
  scanClinitBindings(matchFn: (lit: string) => boolean): Array<{
    literal: string;
    owner: string;
    fieldName: string;
    descriptor: string;
  }>;
}

/**
 * Parses the parts of a class file the inspectors need.
 *
 * <p>Returns `null` when the buffer is not a parseable class file, so a caller can skip an archive
 * entry without treating it as an error.
 */
export function inspectClassBytecode(buf: Buffer): ParsedClassInfo | null {
  const cp = parseConstantPool(buf);
  if (!cp) return null;
  const { nextOffset: offset, utf8Map, classNameIndices, stringTargetIndices, fieldRefRaw, nameAndTypeRaw } = cp;

  if (offset + 8 > buf.length) {
    return null;
  }

  const thisClassIdx = buf.readUInt16BE(offset + 2);
  const thisClassInternal = utf8Map.get(classNameIndices.get(thisClassIdx) ?? -1) ?? '';
  const thisClassDot = thisClassInternal.replace(/\//g, '.');
  const afterSuperOffset = offset + 6;

  const stringCpValues = new Map<number, string>();
  const stringLiterals: string[] = [];
  for (const [cpIdx, utfIdx] of stringTargetIndices.entries()) {
    const value = utf8Map.get(utfIdx);
    if (value !== undefined) {
      stringCpValues.set(cpIdx, value);
      stringLiterals.push(value);
    }
  }

  const fieldRefResolved = new Map<number, { owner: string; name: string; descriptor: string }>();
  const fieldRefs: Array<{ owner: string; name: string; descriptor: string }> = [];
  for (const [cpIdx, raw] of fieldRefRaw.entries()) {
    const owner = utf8Map.get(classNameIndices.get(raw.classIdx) ?? -1);
    const nat = nameAndTypeRaw.get(raw.natIdx);
    const name = nat ? utf8Map.get(nat.nameIdx) : undefined;
    const descriptor = nat ? utf8Map.get(nat.descIdx) : undefined;
    if (owner && name && descriptor) {
      const resolved = { owner, name, descriptor };
      fieldRefResolved.set(cpIdx, resolved);
      fieldRefs.push(resolved);
    }
  }

  return {
    thisClassInternal,
    thisClassDot,
    stringLiterals,
    fieldRefs,
    scanClinitBindings(matchFn) {
      const bindings: Array<{ literal: string; owner: string; fieldName: string; descriptor: string }> = [];
      let pos = afterSuperOffset;
      if (pos + 2 > buf.length) return bindings;
      const ifaceCount = buf.readUInt16BE(pos);
      pos += 2 + ifaceCount * 2;

      // Scan static final String ConstantValue fields as well
      if (pos + 2 > buf.length) return bindings;
      const fieldsCount = buf.readUInt16BE(pos);
      pos += 2;
      for (let f = 0; f < fieldsCount; f++) {
        if (pos + 8 > buf.length) return bindings;
        const fieldAccess = buf.readUInt16BE(pos);
        const fieldName = utf8Map.get(buf.readUInt16BE(pos + 2)) ?? '';
        const fieldDesc = utf8Map.get(buf.readUInt16BE(pos + 4)) ?? '';
        const attrCount = buf.readUInt16BE(pos + 6);
        pos += 8;
        for (let a = 0; a < attrCount; a++) {
          if (pos + 6 > buf.length) return bindings;
          const attrName = utf8Map.get(buf.readUInt16BE(pos));
          const attrLen = buf.readUInt32BE(pos + 2);
          const attrStart = pos + 6;
          pos = attrStart + attrLen;
          if (
            (fieldAccess & 0x0008) !== 0 &&
            fieldDesc === 'Ljava/lang/String;' &&
            attrName === 'ConstantValue' &&
            attrLen === 2 &&
            attrStart + 2 <= buf.length
          ) {
            const value = stringCpValues.get(buf.readUInt16BE(attrStart));
            if (value !== undefined && matchFn(value)) {
              bindings.push({
                literal: value,
                owner: thisClassInternal,
                fieldName,
                descriptor: fieldDesc,
              });
            }
          }
        }
      }

      // Scan <clinit> and static helper methods
      if (pos + 2 > buf.length) return bindings;
      const methodsCount = buf.readUInt16BE(pos);
      pos += 2;
      for (let m = 0; m < methodsCount; m++) {
        if (pos + 8 > buf.length) return bindings;
        const methodAccess = buf.readUInt16BE(pos);
        const nameIdx = buf.readUInt16BE(pos + 2);
        const attrCount = buf.readUInt16BE(pos + 6);
        const methodName = utf8Map.get(nameIdx);
        const isStatic = (methodAccess & 0x0008) !== 0;
        pos += 8;

        for (let a = 0; a < attrCount; a++) {
          if (pos + 6 > buf.length) return bindings;
          const attrName = utf8Map.get(buf.readUInt16BE(pos));
          const attrLen = buf.readUInt32BE(pos + 2);
          const attrStart = pos + 6;
          pos = attrStart + attrLen;

          if ((methodName === '<clinit>' || isStatic) && attrName === 'Code' && attrStart + 8 <= buf.length) {
            const codeLen = buf.readUInt32BE(attrStart + 4);
            const codeStart = attrStart + 8;
            if (codeStart + codeLen <= buf.length) {
              const code = buf.subarray(codeStart, codeStart + codeLen);
              const recentMatches: Array<{ literal: string; pc: number }> = [];
              let pc = 0;
              while (pc < code.length) {
                const op = code[pc]!;
                const step = getJvmOpcodeLength(code, pc);
                if (step <= 0 || pc + step > code.length) break;

                if (op === 0x12 || op === 0x13) {
                  const cpIdx = op === 0x12 ? code[pc + 1]! : code.readUInt16BE(pc + 1);
                  const literal = stringCpValues.get(cpIdx);
                  if (literal !== undefined && matchFn(literal)) {
                    recentMatches.push({ literal, pc });
                    if (recentMatches.length > 6) {
                      recentMatches.shift();
                    }
                  }
                } else if (op === 0xb3) {
                  while (recentMatches.length > 0 && pc - recentMatches[0]!.pc > STATIC_BINDING_WINDOW_BYTES) {
                    recentMatches.shift();
                  }
                  if (recentMatches.length > 0) {
                    const fieldIdx = code.readUInt16BE(pc + 1);
                    const fRef = fieldRefResolved.get(fieldIdx);
                    if (fRef && fRef.owner === thisClassInternal) {
                      const matched = recentMatches.pop()!;
                      bindings.push({
                        literal: matched.literal,
                        owner: fRef.owner,
                        fieldName: fRef.name,
                        descriptor: fRef.descriptor,
                      });
                    }
                  }
                }
                pc += step;
              }
            }
          }
        }
      }
      return bindings;
    },
  };
}
