import type { Metadata, Viewport } from "next";
import Link from "next/link";
import type { ReactNode } from "react";
import { DatasetBanner } from "@/components/DatasetBanner";
import { Nav, ThemeToggle } from "@/components/Chrome";
import { API_BASE } from "@/lib/api";
import "./globals.css";

export const metadata: Metadata = {
  title: "Heads Down traction",
  description:
    "Public, verifiable traction for Heads Down, the phone-gated ORE rig: rigs, retention, dark hours, digs and share of ORE miners, every number linked to on-chain data.",
  robots: { index: true, follow: true },
};

export const viewport: Viewport = {
  themeColor: "#121314",
  colorScheme: "dark",
  width: "device-width",
  initialScale: 1,
};

// Static export cannot send headers, so the policy ships as a meta tag. Next's hydration
// needs inline scripts; data may only be fetched from this origin and the configured API.
const apiOrigin = API_BASE ? new URL(API_BASE).origin : "";
const csp = [
  "default-src 'self'",
  "script-src 'self' 'unsafe-inline'",
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data:",
  `connect-src 'self'${apiOrigin ? ` ${apiOrigin}` : ""}`,
  "object-src 'none'",
  "base-uri 'none'",
  "form-action 'none'",
].join("; ");

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en" data-theme="dark">
      <head>
        <meta httpEquiv="Content-Security-Policy" content={csp} />
        <meta name="referrer" content="no-referrer" />
      </head>
      <body>
        <a href="#main" className="small" style={{ position: "absolute", left: -9999 }}>
          Skip to content
        </a>
        <header className="site-header">
          <div className="wrap">
            <Link className="brand" href="/">
              <span className="brand-dot" aria-hidden="true" />
              Heads Down <small>traction</small>
            </Link>
            <Nav />
            <ThemeToggle />
          </div>
        </header>
        <DatasetBanner />
        <main id="main" className="wrap">
          {children}
        </main>
        <footer className="site-footer">
          <div className="wrap">
            Every metric is recomputed from on-chain data: heads_down program events, ORE&apos;s own DeployEvents signed by
            the Heads Down Executor PDA, and ORE ResetEvents. Powered by{" "}
            <a href="https://ore.com" target="_blank" rel="noopener noreferrer">
              ORE
            </a>
            . Heads Down never custodies funds; each rig mines in its owner&apos;s own ORE Automation account.
            {API_BASE ? (
              <>
                {" "}
                API spec:{" "}
                <a href={`${API_BASE}/openapi.json`} target="_blank" rel="noopener noreferrer">
                  openapi.json
                </a>
                .
              </>
            ) : null}
          </div>
        </footer>
      </body>
    </html>
  );
}
