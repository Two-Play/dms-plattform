/** A piece of a search snippet: plain text, highlighted or not. */
export interface SnippetPart {
  text: string
  highlighted: boolean
}

/**
 * Splits a snippet into text parts at its `<mark>…</mark>` highlights. Everything else stays
 * literal text: other markup is never interpreted, so the snippet can be rendered as React text
 * nodes without `dangerouslySetInnerHTML`. Unbalanced tags are tolerated (a missing `</mark>`
 * highlights to the end).
 */
export function snippetParts(snippet: string): SnippetPart[] {
  const parts: SnippetPart[] = []
  let highlighted = false
  for (const piece of snippet.split(/(<\/?mark>)/i)) {
    const tag = piece.toLowerCase()
    if (tag === '<mark>') highlighted = true
    else if (tag === '</mark>') highlighted = false
    else if (piece) {
      const last = parts.at(-1)
      if (last?.highlighted === highlighted) last.text += piece
      else parts.push({ text: piece, highlighted })
    }
  }
  return parts
}
