/**
 * Reads a JVM class file's constant pool.
 *
 * <p>The pool is the only part of a class file that names things: every string literal, class
 * reference, and field reference a class makes lives here. Both the string search and the ID tracer
 * need it, and both must read it exactly the same way (modified UTF-8, long/double taking two slots),
 * so parsing lives in one function rather than once per consumer.
 */

/** Decodes the JVM's modified UTF-8 (NUL as 0xC0 0x80, surrogate pairs encoded separately). */
export function decodeModifiedUtf8(buf: Buffer): string {
  // Fast path when no MUTF-8 2-byte NUL (0xC0 0x80) or 3-byte surrogate prefix (0xED) exists
  if (!buf.includes(0xc0) && !buf.includes(0xed)) {
    return buf.toString('utf8');
  }
  const units: number[] = [];
  let i = 0;
  while (i < buf.length) {
    const b1 = buf[i]!;
    if ((b1 & 0x80) === 0) {
      units.push(b1);
      i += 1;
    } else if ((b1 & 0xe0) === 0xc0 && i + 1 < buf.length) {
      const b2 = buf[i + 1]!;
      units.push(((b1 & 0x1f) << 6) | (b2 & 0x3f));
      i += 2;
    } else if ((b1 & 0xf0) === 0xe0 && i + 2 < buf.length) {
      const b2 = buf[i + 1]!;
      const b3 = buf[i + 2]!;
      units.push(((b1 & 0x0f) << 12) | ((b2 & 0x3f) << 6) | (b3 & 0x3f));
      i += 3;
    } else {
      return buf.toString('utf8');
    }
  }
  return String.fromCharCode(...units);
}

/** The pool entries this reader keeps: names, class refs, string refs, and member refs. */
export interface ParsedConstantPool {
  /** Offset just past the pool, i.e. the access-flags word of the class header. */
  nextOffset: number;
  utf8Map: Map<number, string>;
  classNameIndices: Map<number, number>;
  stringTargetIndices: Map<number, number>;
  fieldRefRaw: Map<number, { classIdx: number; natIdx: number }>;
  nameAndTypeRaw: Map<number, { nameIdx: number; descIdx: number }>;
}

/**
 * Parses the constant pool, returning `null` for anything that is not a class file.
 *
 * <p>Truncated input returns `null` rather than throwing: a JAR can contain a non-class file with a
 * `.class` name, and that is a fact about the archive, not a reason to fail the whole search.
 */
export function parseConstantPool(buf: Buffer): ParsedConstantPool | null {
  if (buf.length < 10 || buf.readUInt32BE(0) !== 0xcafebabe) {
    return null;
  }
  const cpCount = buf.readUInt16BE(8);
  const utf8Map = new Map<number, string>();
  const classNameIndices = new Map<number, number>();
  const stringTargetIndices = new Map<number, number>();
  const fieldRefRaw = new Map<number, { classIdx: number; natIdx: number }>();
  const nameAndTypeRaw = new Map<number, { nameIdx: number; descIdx: number }>();

  let offset = 10;
  for (let i = 1; i < cpCount && offset < buf.length; i++) {
    const tag = buf[offset++]!;
    switch (tag) {
      case 1: {
        // CONSTANT_Utf8
        if (offset + 2 > buf.length) return null;
        const len = buf.readUInt16BE(offset);
        offset += 2;
        if (offset + len > buf.length) return null;
        utf8Map.set(i, decodeModifiedUtf8(buf.subarray(offset, offset + len)));
        offset += len;
        break;
      }
      case 3: // CONSTANT_Integer
      case 4: // CONSTANT_Float
        offset += 4;
        break;
      case 5: // CONSTANT_Long
      case 6: // CONSTANT_Double
        offset += 8;
        i++; // Occupies 2 constant-pool entries (JVMS §4.4.5)
        break;
      case 7: // CONSTANT_Class
        if (offset + 2 > buf.length) return null;
        classNameIndices.set(i, buf.readUInt16BE(offset));
        offset += 2;
        break;
      case 8: // CONSTANT_String
        if (offset + 2 > buf.length) return null;
        stringTargetIndices.set(i, buf.readUInt16BE(offset));
        offset += 2;
        break;
      case 9: // CONSTANT_Fieldref
        if (offset + 4 > buf.length) return null;
        fieldRefRaw.set(i, {
          classIdx: buf.readUInt16BE(offset),
          natIdx: buf.readUInt16BE(offset + 2),
        });
        offset += 4;
        break;
      case 10: // CONSTANT_Methodref
      case 11: // CONSTANT_InterfaceMethodref
      case 17: // CONSTANT_Dynamic
      case 18: // CONSTANT_InvokeDynamic
        offset += 4;
        break;
      case 12: // CONSTANT_NameAndType
        if (offset + 4 > buf.length) return null;
        nameAndTypeRaw.set(i, {
          nameIdx: buf.readUInt16BE(offset),
          descIdx: buf.readUInt16BE(offset + 2),
        });
        offset += 4;
        break;
      case 15: // CONSTANT_MethodHandle
        offset += 3;
        break;
      case 16: // CONSTANT_MethodType
      case 19: // CONSTANT_Module
      case 20: // CONSTANT_Package
        offset += 2;
        break;
      default:
        return null;
    }
  }

  return {
    nextOffset: offset,
    utf8Map,
    classNameIndices,
    stringTargetIndices,
    fieldRefRaw,
    nameAndTypeRaw,
  };
}

/** Every UTF-8 entry of a class file, in pool order. Used for class-string content search. */
export function extractClassUtf8Strings(buf: Buffer): string[] {
  const cp = parseConstantPool(buf);
  if (!cp) return [];
  const strings: string[] = [];
  for (const value of cp.utf8Map.values()) {
    if (value.length > 0) {
      strings.push(value);
    }
  }
  return strings;
}
