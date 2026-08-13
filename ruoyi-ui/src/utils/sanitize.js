import DOMPurify from 'dompurify'

const NOTICE_TAGS = [
  'p', 'br', 'strong', 'b', 'em', 'i', 'u', 's', 'h1', 'h2', 'h3',
  'ul', 'ol', 'li', 'blockquote', 'table', 'thead', 'tbody', 'tr', 'th', 'td',
  'a', 'img', 'span'
]

export function sanitizeNoticeHtml(value) {
  const clean = DOMPurify.sanitize(String(value || ''), {
    ALLOWED_TAGS: NOTICE_TAGS,
    ALLOWED_ATTR: ['href', 'src', 'alt', 'title', 'target', 'rel', 'colspan', 'rowspan'],
    ALLOW_DATA_ATTR: false,
    ALLOW_UNKNOWN_PROTOCOLS: false,
    FORBID_TAGS: ['svg', 'math', 'style', 'script', 'iframe', 'object', 'embed', 'form'],
    FORBID_ATTR: ['style', 'srcset'],
    RETURN_DOM: false,
    IN_PLACE: false
  })
  const template = document.createElement('template')
  template.innerHTML = clean
  template.content.querySelectorAll('a[href]').forEach((node) => {
    if (!/^https:\/\//i.test(node.getAttribute('href') || '')) node.removeAttribute('href')
    node.setAttribute('rel', 'noopener noreferrer')
  })
  template.content.querySelectorAll('img[src]').forEach((node) => {
    if (!/^\/attachments\/[A-Za-z0-9-]+\/content$/.test(node.getAttribute('src') || '')) node.remove()
  })
  return template.innerHTML
}

export function escapeHtml(value) {
  return String(value || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;')
}
