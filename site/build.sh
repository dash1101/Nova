#!/bin/sh
# Wrap body.html into a full index.html (doctype, head, favicon).
cd "$(dirname "$0")"
{ printf '<!doctype html>\n<html lang="en">\n<head>\n<meta charset="utf-8">\n<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">\n<meta name="description" content="Nova is an open-source remote for your Linux home server: health, containers, drives, backups and alerts, with every request signed by your phone.">\n<link rel="icon" href="icon.svg" type="image/svg+xml">\n'
  sed -n '/^<title>/,/^<\/style>/p' body.html; printf '</head>\n<body>\n'; sed -n '/^<div class="wrap">/,$p' body.html; printf '</body>\n</html>\n'; } > index.html
echo "built site/index.html"
