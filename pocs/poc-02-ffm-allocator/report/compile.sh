#!/bin/bash
set -e

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "Checking LaTeX environment..."

if ! command -v pdflatex &> /dev/null; then
    echo -e "${RED}[ERROR] pdflatex is not installed.${NC}"
    echo -e "To compile this report on CachyOS (Arch), run:"
    echo -e "  sudo pacman -S texlive-basic texlive-latexextra texlive-bin"
    exit 1
fi

TEX_FILE="main.tex"
if [ ! -f "$TEX_FILE" ]; then
    TEX_FILE="report.tex"
fi

echo -e "Compiling LaTeX document ($TEX_FILE)..."
pdflatex -interaction=nonstopmode "$TEX_FILE"
pdflatex -interaction=nonstopmode "$TEX_FILE" # Run twice for cross-references/tables

echo -e "${GREEN}[SUCCESS] Compilation complete! Output generated at report.pdf${NC}"
