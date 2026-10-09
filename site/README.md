# Nova website

`index.html` is the public page (GitHub Pages or Cloudflare Pages: serve this folder as-is).
`body.html` is the same page without the document wrapper, for previews. Edit `body.html`, then rebuild:

    cd site && ./build.sh
