import type { NextConfig } from "next";

// Fully static site: `next build` writes out/ (HTML/JS/CSS only). All data is fetched in the
// browser from the indexer's public API (NEXT_PUBLIC_HD_API_BASE). No server, no secrets.
const config: NextConfig = {
  output: "export",
  trailingSlash: true,
  poweredByHeader: false,
  reactStrictMode: true,
  images: { unoptimized: true },
};

export default config;
