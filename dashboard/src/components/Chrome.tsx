"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import type { ApiState } from "@/lib/useApi";

const LINKS = [
  { href: "/", label: "Overview" },
  { href: "/cohorts/", label: "Retention" },
  { href: "/share/", label: "Share of ORE miners" },
  { href: "/digs/", label: "Recent digs" },
  { href: "/milestones/", label: "ORE milestones" },
  { href: "/method/", label: "Method" },
];

export function Nav() {
  const path = usePathname() ?? "/";
  const norm = path.endsWith("/") ? path : `${path}/`;
  return (
    <nav className="nav" aria-label="Sections">
      {LINKS.map((l) => (
        <Link key={l.href} href={l.href} aria-current={norm === l.href ? "page" : undefined}>
          {l.label}
        </Link>
      ))}
    </nav>
  );
}

/** Dark by default. Light is opt-in and remembered in this browser only. */
export function ThemeToggle() {
  const [theme, setTheme] = useState<"dark" | "light">("dark");
  useEffect(() => {
    try {
      if (localStorage.getItem("hd-theme") === "light") setTheme("light");
    } catch {
      /* storage unavailable: stay dark */
    }
  }, []);
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
  }, [theme]);
  const next = theme === "dark" ? "light" : "dark";
  return (
    <button
      type="button"
      className="theme-toggle"
      aria-label={`Switch to ${next} theme`}
      onClick={() => {
        setTheme(next);
        try {
          localStorage.setItem("hd-theme", next);
        } catch {
          /* ignore */
        }
      }}
    >
      {theme === "dark" ? "Light" : "Dark"}
    </button>
  );
}

/** Loading / error / unconfigured states, shared by every page. */
export function Loadable<T>({ state, children }: { state: ApiState<T>; children: (data: T, simulated: boolean, asOf: number) => ReactNode }) {
  if (state.status === "unconfigured") {
    return (
      <div className="card state">
        This build has no API configured. Set <code>NEXT_PUBLIC_HD_API_BASE</code> to the indexer&apos;s public URL and rebuild
        (for a local demo: <code>cd services/indexer &amp;&amp; pnpm demo</code>, then <code>NEXT_PUBLIC_HD_API_BASE=http://127.0.0.1:8787</code>).
      </div>
    );
  }
  if (state.status === "loading") return <div className="card state" aria-busy="true">Loading…</div>;
  if (state.status === "error") {
    return (
      <div className="card state error" role="alert">
        Could not load data: {state.message}
      </div>
    );
  }
  return <>{children(state.env.data, state.env.dataset.simulated, state.env.asOf)}</>;
}
