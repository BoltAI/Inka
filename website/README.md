# Inka website

Landing site for Inka, designed e-ink-first for BOOX tablets.

- Static files, no build step: `index.html` plus the privacy policy at `privacy/index.html`. The only JavaScript is a few lines for the screenshot strip arrows.
- Screenshots live in `screenshots/` (full size, 1860x2480, also the canonical copies for the Play Store listing) and `screenshots/thumbs/` (560px wide). Regenerate thumbs with `sips --resampleWidth 560`.
- Pure black/white/gray palette, no animations or fixed elements, so nothing ghosts on an e-ink refresh.
- Body type is [Literata](https://fonts.google.com/specimen/Literata) (an open typeface designed for e-reading, in the spirit of Bookerly); handwritten accents use Caveat. Both load from Google Fonts.

## Preview locally

```bash
python3 -m http.server 4173 --directory website
```

## Deploy

Deployed with Cloudflare Pages Git integration: framework preset None, no build command, build output directory `website`. Pushes to `main` deploy automatically.

## TODO

- Replace the mock notebook figure in the hero with a real screenshot or demo video.
- The Google Play link points at `co.podzim.inka`; it goes live once the store listing is published.
- Deploy the folder to a public URL and update the privacy policy URL in the Play submission pack (`docs/design/play-store-submission/submission-pack.md`) to match.
