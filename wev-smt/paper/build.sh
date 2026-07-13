#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
pdflatex -interaction=nonstopmode wev-smt.tex
bibtex wev-smt
pdflatex -interaction=nonstopmode wev-smt.tex
pdflatex -interaction=nonstopmode wev-smt.tex
echo "Build complete: wev-smt.pdf"
