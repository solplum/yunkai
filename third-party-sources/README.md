# Bundled runtime corresponding sources

All ordered parts accompany the binary-containing yunkai-next tree. Reconstruct the complete source archive (POSIX shell):

```sh
cd third-party-sources
shasum -a 256 -c SHA256SUMS
cat yunkai-mika-0.1.10-corresponding-sources.tar.gz.part[0-9][0-9] > yunkai-mika-0.1.10-corresponding-sources.tar.gz
shasum -a 256 yunkai-mika-0.1.10-corresponding-sources.tar.gz
tar -xzf yunkai-mika-0.1.10-corresponding-sources.tar.gz
```

Complete archive SHA256: `d10363e1eb967f9d09c06f64b0a3e5255c94bbf413cf2f83a5f03ae50d768d97`.

Contains pinned native PRoot/talloc and Alpine sources, package build recipes, patches, cargo sources, locks and build scripts. The 261 internal SHA256 manifest entries were checked. This is source availability, not a claim that every binary was reproducibly rebuilt. Preserve all included licenses and notices when redistributing. No APK, signing key or user credentials are included.
