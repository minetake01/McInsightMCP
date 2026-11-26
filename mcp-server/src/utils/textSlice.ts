export interface TextSliceOptions {
  startLine?: number;
  endLine?: number;
  maxChars?: number;
}

export interface TextSliceResult {
  content: string;
  startLine: number;
  endLine: number;
  totalLines: number;
  truncated: boolean;
}

const DEFAULT_MAX_CHARS = 50_000;

export function sliceTextOutput(text: string, opts: TextSliceOptions = {}): TextSliceResult {
  if (text.length === 0) {
    return { content: '', startLine: 1, endLine: 0, totalLines: 0, truncated: false };
  }
  const normalized = text.replace(/\r\n/g, '\n');
  const body = normalized.endsWith('\n') ? normalized.slice(0, -1) : normalized;
  const lines = body.split('\n');
  const totalLines = lines.length;

  const start = Math.max(1, Math.min(opts.startLine ?? 1, totalLines));
  const requestedEnd = opts.endLine !== undefined ? Math.min(opts.endLine, totalLines) : totalLines;
  const end = Math.max(start, requestedEnd);

  const selected = lines.slice(start - 1, end).join('\n');
  const limit = opts.maxChars ?? DEFAULT_MAX_CHARS;
  const rangeTruncated = start > 1 || end < totalLines;

  if (selected.length > limit) {
    return {
      content: selected.slice(0, limit),
      startLine: start,
      endLine: start + selected.slice(0, limit).split('\n').length - 1,
      totalLines,
      truncated: true,
    };
  }
  return {
    content: selected,
    startLine: start,
    endLine: end,
    totalLines,
    truncated: rangeTruncated,
  };
}
