# IDPC evaluation documents — ingested document ids

> Uploaded 28 July 2026 via `upload-document.sh` through the APIM gateway
> (`spnl-apim-int-gw.cpp.nonlive/api-cp-ai-rag`) into the nonlive evaluation environment.
> All five reached `INGESTION_SUCCESS`. The source PDFs live locally under
> `src/main/resources/idpc-documents/` (git-ignored — case files are never committed).

| File | documentId |
|---|---|
| idpc1.pdf | `349ef56e-d211-45a5-aa06-5dcc2929b6e9` |
| idpc2.pdf | `50b5da6c-ad06-43b1-a798-8ffba4bda4a3` |
| idpc3.pdf | `b7fcebd1-0ee3-4908-9215-769d9aebdd28` |
| idpc4.pdf | `c9bcfab7-2fe3-4b00-8128-26371c6d58ba` |
| idpc5.pdf | `3f326c23-61d1-46ab-91d0-88e4d7a8614a` |

Ready to paste into `.env` (replace or extend the existing list; every query in the set runs
against every id, so 5 ids × 10 queries × 2 models = 100 generation calls per repetition):

```
HARNESS_DOCUMENT_IDS=349ef56e-d211-45a5-aa06-5dcc2929b6e9,50b5da6c-ad06-43b1-a798-8ffba4bda4a3,b7fcebd1-0ee3-4908-9215-769d9aebdd28,c9bcfab7-2fe3-4b00-8128-26371c6d58ba,3f326c23-61d1-46ab-91d0-88e4d7a8614a
```

These ids are environment-specific: re-running the upload (or targeting another environment)
mints new ids — update this file and `.env` together.
