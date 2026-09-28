# Polices des PDF (DA « Partition »)

Embarquées par `HtmlPdfService` (liste dans `PdfTheme.fonts`). Toutes sous licence SIL OFL 1.1 (fichiers `*-OFL.txt`).

| Fichier | Source |
| --- | --- |
| `InstrumentSerif-Regular.ttf`, `InstrumentSerif-Italic.ttf` | `google/fonts`, `ofl/instrumentserif` |
| `IBMPlexMono-Regular.ttf`, `IBMPlexMono-Medium.ttf` | `google/fonts`, `ofl/ibmplexmono` |
| `HankenGrotesk-{Regular,SemiBold,Bold}.ttf` | Instances statiques de `google/fonts`, `ofl/hankengrotesk/HankenGrotesk[wght].ttf` |

openhtmltopdf/PDFBox ne sait pas choisir une graisse dans une police variable, d'où les instances statiques :

```bash
fonttools varLib.instancer "HankenGrotesk[wght].ttf" wght=400 --update-name-table -o HankenGrotesk-Regular.ttf
fonttools varLib.instancer "HankenGrotesk[wght].ttf" wght=600 --update-name-table -o HankenGrotesk-SemiBold.ttf
fonttools varLib.instancer "HankenGrotesk[wght].ttf" wght=700 --update-name-table -o HankenGrotesk-Bold.ttf
```

Aucune de ces polices ne contient l'espace fine insécable (U+202F) utilisée par `NumberFormat` en français : `DocumentTemplateService` la remplace par une espace insécable (U+00A0).
