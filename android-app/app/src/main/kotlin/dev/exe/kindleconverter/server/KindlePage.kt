package dev.exe.kindleconverter.server

import dev.exe.kindleconverter.data.Book

/** A book as shown on the Kindle page. */
data class PageBook(val id: String, val title: String, val author: String, val sizeText: String)

fun Book.toPageBook() = PageBook(
    id = id,
    title = title,
    author = author,
    sizeText = if (azw3Size > 0) "%.1f MB".format(azw3Size / 1_048_576.0) else "",
)

private fun esc(s: String): String = buildString {
    for (c in s) when (c) {
        '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
        '"' -> append("&quot;"); '\'' -> append("&#39;"); else -> append(c)
    }
}

/**
 * Server-rendered, e-ink-first library page for the Kindle Experimental Browser.
 *
 * Constraints baked in: the whole list renders without JavaScript (the old WebKit on
 * many Kindles is ES5-only and often has JS disabled); CSS is inline (one round-trip);
 * borders are solid hairlines (drop shadows are invisible on e-ink); tap targets are
 * large; nothing animates. The newest book sits in a framed slot so it's unmistakable
 * after a slow refresh.
 */
fun renderKindlePage(books: List<PageBook>): String {
    val newest = books.firstOrNull()
    val rest = books.drop(1)
    val body = StringBuilder()

    if (books.isEmpty()) {
        body.append(
            """<div class="empty"><h2>No books yet</h2>
               <p>Convert a book on your phone, then tap Refresh.</p></div>"""
        )
    } else {
        body.append("""<div class="featured"><div class="eyebrow">Newest</div>""")
        body.append(bookBlock(newest!!, featured = true))
        body.append("</div>")
        if (rest.isNotEmpty()) {
            body.append("""<div class="list">""")
            rest.forEach { body.append(bookBlock(it, featured = false)) }
            body.append("</div>")
        }
    }

    val filter = if (books.size > 6) {
        // ES5-only, degrades gracefully: if JS is off the box is inert and all books show.
        """<input id="q" placeholder="Filter by title or author" autocomplete="off">
           <script type="text/javascript">
           (function(){var q=document.getElementById('q');if(!q)return;
           q.onkeyup=function(){var t=q.value.toLowerCase();
           var rows=document.getElementsByClassName('book');
           for(var i=0;i<rows.length;i++){var r=rows[i];
           var hay=(r.getAttribute('data-h')||'').toLowerCase();
           r.style.display=hay.indexOf(t)>=0?'':'none';}};})();
           </script>"""
    } else ""

    return """<!doctype html>
<html><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Your Library</title>
<style>
* { box-sizing: border-box; }
body { margin: 0; background: #FAF8F3; color: #14110B;
       font-family: Georgia, 'Times New Roman', serif;
       font-size: 19px; line-height: 1.4; }
.wrap { max-width: 760px; margin: 0 auto; padding: 22px 20px 64px; }
.masthead { letter-spacing: .22em; font-size: 15px; text-transform: uppercase;
            font-weight: bold; color: #4B3826; }
.rule { border: 0; border-top: 2px solid #14110B; margin: 8px 0 22px; }
.featured { border: 2px solid #14110B; padding: 18px 18px 20px; margin-bottom: 26px; }
.eyebrow { letter-spacing: .2em; font-size: 12px; text-transform: uppercase;
           color: #6B5B43; margin-bottom: 6px; }
.title { font-size: 25px; font-weight: bold; margin: 0 0 4px; }
.featured .title { font-size: 30px; }
.meta { color: #5A5040; font-size: 16px; margin-bottom: 14px; }
.book { padding: 18px 0; border-top: 1px solid #C9C0AE; }
.list .book:first-child { border-top: 0; }
a.get { display: block; text-align: center; text-decoration: none;
        color: #14110B; border: 2px solid #14110B; background: #FFFFFF;
        padding: 14px 16px; font-size: 19px; font-weight: bold; }
a.get:active { background: #14110B; color: #FAF8F3; }
.q-wrap { margin: 0 0 18px; }
#q { width: 100%; padding: 12px 12px; font-size: 18px; font-family: inherit;
     border: 1px solid #8B7C5E; background: #FFFFFF; color: #14110B; }
.empty { text-align: center; padding: 60px 10px; color: #5A5040; }
.empty h2 { font-size: 26px; margin: 0 0 6px; color: #14110B; }
.foot { margin-top: 36px; text-align: center; color: #8B7C5E; font-size: 14px; }
</style></head>
<body><div class="wrap">
<div class="masthead">Your Library</div>
<hr class="rule">
<div class="q-wrap">$filter</div>
$body
<div class="foot">Tap a title to download. Books open from your Kindle Home screen.</div>
</div></body></html>"""
}

private fun bookBlock(b: PageBook, featured: Boolean): String {
    val meta = listOf(b.author, b.sizeText).filter { it.isNotBlank() }.joinToString(" &middot; ")
    val hay = esc("${b.title} ${b.author}")
    return """<div class="book" data-h="$hay">
        <div class="title">${esc(b.title)}</div>
        ${if (meta.isNotBlank()) "<div class=\"meta\">$meta</div>" else ""}
        <a class="get" href="/download/${esc(b.id)}">Download</a>
    </div>"""
}
