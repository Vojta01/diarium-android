"use client";

/**
 * Lightweight Markdown renderer — converts basic MD to HTML.
 * Handles: **bold**, *italic*, ### headings, - lists, line breaks.
 * Keeps it simple to avoid heavy dependencies.
 */
function renderMarkdown(text: string): string {
  if (!text) return "";

  let html = text
    // Escape HTML
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")

    // Bold (**text**)
    .replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>")

    // Italic (*text*)
    .replace(/\*(.+?)\*/g, "<em>$1</em>")

    // ### Headings
    .replace(/^### (.+)$/gm, '<h3 class="text-base font-semibold text-white/90 mt-3 mb-1">$1</h3>')
    .replace(/^## (.+)$/gm, '<h2 class="text-lg font-semibold text-white mt-4 mb-2">$1</h2>')
    .replace(/^# (.+)$/gm, '<h1 class="text-xl font-bold text-white mt-4 mb-2">$1</h1>')

    // Unordered list items
    .replace(/^- (.+)$/gm, '<li class="ml-4 text-white/70">• $1</li>')
    
    // Numbered list items
    .replace(/^\d+\.\s(.+)$/gm, '<li class="ml-4 text-white/70">$1</li>')

    // Double newlines → paragraph breaks
    .replace(/\n\n/g, "</p><p class='mb-2'>")

    // Single newlines → <br> (but not inside list items)
    .replace(/\n/g, "<br/>");

  // Wrap in paragraph if not already
  if (!html.startsWith("<h") && !html.startsWith("<li")) {
    html = "<p class='mb-2'>" + html + "</p>";
  }

  // Clean up empty paragraphs
  html = html.replace(/<p class='mb-2'><\/p>/g, "");
  html = html.replace(/<p class='mb-2'><br\/><\/p>/g, "");

  return html;
}

export function Markdown({ content, className = "" }: { content: string; className?: string }) {
  const html = renderMarkdown(content);

  return (
    <div
      className={`text-white/80 text-sm leading-relaxed ${className}`}
      dangerouslySetInnerHTML={{ __html: html }}
    />
  );
}
