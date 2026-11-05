#!/usr/bin/env bash
set -euo pipefail

# Inventory of executable coupling to the retired YouTube Music backend.
# Persisted identifiers (videoId, legacy URLs) are intentionally not matched:
# they remain readable until all historical data migrations are verified.
pattern='YtMusicRepository|InnerTubeXResolver|Innertube[.]|InnertubeParser|StreamResolver[.]|PoTokenGenerator|PoTokenWebView|YouTubeSource|DesktopYouTubeAuth|DesktopYouTubeSession|youtubei[.]googleapis[.]com|music[.]youtube[.]com'
roots=()
for root in app/src/main shared/src desktopApp/src/main tvApp/src wearApp/src web/app/src; do
  if [[ -d "$root" ]]; then roots+=("$root"); fi
done

output="youtube-removal-references.txt"
: > "$output"
if (( ${#roots[@]} > 0 )); then
  git grep -n -E "$pattern" -- "${roots[@]}" > "$output" || true
fi
total=$(wc -l < "$output" | tr -d ' ')
files=$(cut -d: -f1 "$output" | sort -u | wc -l | tr -d ' ')
echo "Retired backend references: $total lines across $files source files"
echo "This report is an inventory, not an authorization to delete archived identities."
echo "All remaining references:"
cat "$output"
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  {
    echo "### Retired YouTube backend audit"
    echo "- Source references: **$total**"
    echo "- Source files: **$files**"
    echo "- Persisted historical IDs intentionally excluded"
    echo "- See the full artifact for exact paths and lines"
  } >> "$GITHUB_STEP_SUMMARY"
fi
