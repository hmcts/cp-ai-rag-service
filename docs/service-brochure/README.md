# Service brochure

Print-styled introduction material for the CP AI RAG Service:

| File | Purpose |
|---|---|
| `rag-as-a-service.pdf` | 13-page service guide — capabilities, architecture, consuming the API, trust/assurance, adoption patterns. For service owners, delivery teams and assurance functions. |
| `rag-service-one-pager.pdf` | Single-page brief — scope, technical approach, responsible AI, cost vs benefit (pilot actuals + national projections), constraints to scaling nationally. Sources are linked in the footer (CROWN Confluence pages + the model-migration cost projection in this repo). |
| `src/*.html` | Editable sources. The PDFs are rendered from these — edit the HTML, re-render, commit both. |

## Regenerating a PDF

From `src/`:

```bash
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new \
  --no-pdf-header-footer --print-to-pdf=../rag-service-one-pager.pdf rag-service-one-pager.html
```

(Same pattern for `rag-as-a-service.html`. Any Chromium works; pages are manually
paginated 210×297 mm blocks, so after content edits check each page for overflow
past the footer rule.)
