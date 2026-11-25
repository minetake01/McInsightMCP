/**
 * JVM instruction lengths.
 *
 * <p>Walking a method's bytecode requires knowing where each instruction ends, and the only way to
 * know that without a full decoder is the opcode table. Only the lengths are needed here: the tracer
 * looks for `ldc` and `putstatic` and must know how far to advance past everything else, including
 * the variable-length `tableswitch`, `lookupswitch`, and `wide` forms.
 */

/** The byte length of the instruction starting at `pc`, or -1 when it cannot be determined. */
export function getJvmOpcodeLength(code: Buffer, pc: number): number {
  const op = code[pc]!;
  if (op === 0xc4) {
    // wide: 4 bytes normally, 6 for the wide iinc form
    if (pc + 1 >= code.length) return -1;
    const sub = code[pc + 1]!;
    return sub === 0x84 ? 6 : 4;
  }
  if (op === 0xaa) {
    // tableswitch: 1 + padding + 12 + (high - low + 1) * 4
    const pad = (4 - ((pc + 1) % 4)) % 4;
    const pos = pc + 1 + pad;
    if (pos + 12 > code.length) return -1;
    const low = code.readInt32BE(pos + 4);
    const high = code.readInt32BE(pos + 8);
    if (high < low || high - low > 65536) return -1;
    return 1 + pad + 12 + (high - low + 1) * 4;
  }
  if (op === 0xab) {
    // lookupswitch: 1 + padding + 8 + npairs * 8
    const pad = (4 - ((pc + 1) % 4)) % 4;
    const pos = pc + 1 + pad;
    if (pos + 8 > code.length) return -1;
    const npairs = code.readInt32BE(pos + 4);
    if (npairs < 0 || npairs > 65536) return -1;
    return 1 + pad + 8 + npairs * 8;
  }
  switch (op) {
    case 0x10: // bipush
    case 0x12: // ldc
    case 0x15: // iload
    case 0x16: // lload
    case 0x17: // fload
    case 0x18: // dload
    case 0x19: // aload
    case 0x36: // istore
    case 0x37: // lstore
    case 0x38: // fstore
    case 0x39: // dstore
    case 0x3a: // astore
    case 0xa9: // ret
    case 0xbc: // newarray
      return 2;
    case 0x11: // sipush
    case 0x13: // ldc_w
    case 0x14: // ldc2_w
    case 0x84: // iinc
    case 0x99: // ifeq
    case 0x9a: // ifne
    case 0x9b: // iflt
    case 0x9c: // ifge
    case 0x9d: // ifgt
    case 0x9e: // ifle
    case 0x9f: // if_icmpeq
    case 0xa0: // if_icmpne
    case 0xa1: // if_icmplt
    case 0xa2: // if_icmpge
    case 0xa3: // if_icmpgt
    case 0xa4: // if_icmple
    case 0xa5: // if_acmpeq
    case 0xa6: // if_acmpne
    case 0xa7: // goto
    case 0xa8: // jsr
    case 0xb2: // getstatic
    case 0xb3: // putstatic
    case 0xb4: // getfield
    case 0xb5: // putfield
    case 0xb6: // invokevirtual
    case 0xb7: // invokespecial
    case 0xb8: // invokestatic
    case 0xbb: // new
    case 0xbd: // anewarray
    case 0xc0: // checkcast
    case 0xc1: // instanceof
    case 0xc6: // ifnull
    case 0xc7: // ifnonnull
      return 3;
    case 0xc5: // multianewarray
      return 4;
    case 0xb9: // invokeinterface
    case 0xba: // invokedynamic
    case 0xc8: // goto_w
    case 0xc9: // jsr_w
      return 5;
    default:
      return 1;
  }
}
